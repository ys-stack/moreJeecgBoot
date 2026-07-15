package org.jeecg.modules.airag.practice.chat.service;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.chat.entity.AiChatMessage;
import org.jeecg.modules.airag.practice.chat.entity.AiChatSession;
import org.jeecg.modules.airag.practice.chat.entity.AiToolChatCase;
import org.jeecg.modules.airag.practice.chat.mapper.AiChatMessageMapper;
import org.jeecg.modules.airag.practice.chat.mapper.AiChatSessionMapper;
import org.jeecg.modules.airag.practice.chat.service.IAiToolChatCaseService;
import org.jeecg.modules.airag.practice.threadpool.PracticeThreadPool;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对话记忆服务
 *
 * 在生产级 RAG/Agent 系统中，对话记忆是核心基础设施。
 * 本服务实现"滑动窗口 + 摘要压缩"双层记忆架构：
 *
 * ┌──────────────────────────────────────────────┐
 * │            发给模型的上下文                     │
 * │                                              │
 * │  [SystemMessage: RAG/Agent 指令]             │
 * │  [SystemMessage: 摘要（长期记忆）]  ← 压缩    │
 * │  [UserMessage: 最近第1轮]          ← 原始     │
 * │  [AiMessage:   最近第1轮]          ← 原始     │
 * │  [UserMessage: 最近第2轮]                     │
 * │  [AiMessage:   最近第2轮]                     │
 * │  ...                                         │
 * │  [UserMessage: 当前问题]                      │
 * └──────────────────────────────────────────────┘
 *
 * 短期记忆：最近 SHORT_TERM_COUNT 条原始消息（滑动窗口）
 * 长期记忆：更早的消息被模型压缩成一段摘要，存在 ai_chat_session.summary
 * 触发条件：消息数 > SUMMARY_TRIGGER_COUNT 时异步生成/更新摘要
 *
 * 设计要点：
 * 1. Token 预算控制：摘要 + 短期消息总 token 不超过上下文预算
 * 2. 摘要累积合并：新摘要在旧摘要基础上合并，不会从零重建
 * 3. 异步不阻塞：摘要生成在独立线程池执行，不影响对话响应
 * 4. 截断保护：摘要最大长度 800 字符，防止无限膨胀
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-29
 */
@Slf4j
@Service
public class ConversationMemoryService {

    /** 短期记忆保留的最近消息数（user + assistant 各算一条） */
    private static final int SHORT_TERM_COUNT = 10;

    /** 触发摘要生成的消息总数阈值 */
    private static final int SUMMARY_TRIGGER_COUNT = 15;

    /** 摘要最大字符长度 */
    private static final int SUMMARY_MAX_LENGTH = 800;

    /** 传给模型的历史文本最大字符长度（防 prompt 爆炸） */
    private static final int HISTORY_TEXT_MAX_LENGTH = 6000;

    /** 中文字符的 token 估算系数 */
    private static final double ZH_TOKEN_RATIO = 1.5;

    /** 英文/数字/符号的 token 估算系数 */
    private static final double EN_TOKEN_RATIO = 0.75;

    /** 摘要生成 Prompt */
    private static final String SUMMARY_PROMPT_TEMPLATE = """
            你是一个对话摘要助手。请将以下对话历史压缩为一段简洁的摘要。
            
            要求：
            1. 保留关键实体：人名、订单号、工单号、产品名、日期、金额等
            2. 保留用户意图和偏好：用户想要什么、不喜欢什么、有什么约束
            3. 保留已完成的操作：查询了什么、创建了什么、结果如何
            4. 删除寒暄、重复内容和无关细节
            5. 用第三人称描述（如"用户询问了订单B100的状态，系统返回订单已完成"）
            6. 摘要长度不超过 %d 个字符
            %s
            以下是需要压缩的对话内容：
            %s
            
            请直接输出摘要文本，不要加"摘要："前缀或其他格式。
            """;

    /** 会话结束总结 Prompt */
    private static final String SESSION_END_SUMMARY_PROMPT = """
            请为以下对话生成一份结构化总结。

            输出格式：
            ## 对话主题
            （一句话概括本次对话的核心目的）
            
            ## 关键信息
            （列出对话中提到的重要实体和事实，每条一行）
            
            ## 完成的操作
            （列出本次对话中执行的所有操作及结果，如无则写"无"）
            
            ## 待跟进
            （列出需要后续处理的事项，如无则写"无"）
            
            对话内容：
            %s
            """;

    @Resource
    private AiChatMessageMapper messageMapper;

    @Resource
    private AiChatSessionMapper sessionMapper;

    @Resource
    @Qualifier("practiceChatModel")
    private OpenAiChatModel chatModel;

    @Resource
    @Qualifier("practiceStreamPool")
    private PracticeThreadPool streamPool;

    @Resource
    private IAiToolChatCaseService chatCaseService;

    @Value("${practice.ai.model-name:mimo-v2.5-pro}")
    private String modelName;

    // ======================== 核心方法：构建上下文 ========================

    /**
     * 构建对话历史消息列表
     *
     * 返回的消息可以直接拼到模型的 messages 中：
     *   [上层构建的 SystemMessage(RAG/Agent 指令)]
     *   + buildHistoryMessages(sessionId) 的返回值
     *   + [当前 UserMessage]
     *
     * 结构：
     *   [SystemMessage: 摘要（如果有）]
     *   [UserMessage / AiMessage: 最近 N 条原始消息]
     *
     * @param sessionId 会话ID
     * @return 历史消息列表（按时间正序）
     */
    public List<ChatMessage> buildHistoryMessages(String sessionId) {
        return buildHistoryMessages(sessionId, null);
    }

    /**
     * 构建历史消息，并排除当前刚写入数据库的用户消息。
     * RAG 主流程会在调用模型前保存用户消息用于审计，如果不排除，当前问题会同时出现在
     * 历史消息和最终 UserMessage 中，造成重复输入并浪费 token。
     */
    public List<ChatMessage> buildHistoryMessages(String sessionId, String excludedMessageId) {
        List<ChatMessage> result = new ArrayList<>();

        // ① 加载会话摘要（长期记忆）
        AiChatSession session = sessionMapper.selectById(sessionId);
        if (session != null && session.getSummary() != null && !session.getSummary().isBlank()) {
            String summaryText = "以下是本次对话之前历史的摘要，包含了早期的关键信息，请参考：\n"
                    + session.getSummary();
            result.add(new SystemMessage(summaryText));
            log.debug("[Memory] 加载长期记忆摘要: sessionId={}, 摘要长度={}字符",
                    sessionId, session.getSummary().length());
        }

        // ② 加载最近 N 条原始消息（短期记忆）
        List<AiChatMessage> recentMessages = excludedMessageId == null || excludedMessageId.isBlank()
                ? messageMapper.loadRecentMessages(sessionId, SHORT_TERM_COUNT)
                : messageMapper.loadRecentMessagesExcluding(
                        sessionId,
                        excludedMessageId,
                        SHORT_TERM_COUNT
                );
        for (AiChatMessage msg : recentMessages) {
            switch (msg.getRole()) {
                case "user" -> result.add(new UserMessage(msg.getContent()));
                case "assistant" -> result.add(new AiMessage(msg.getContent()));
            }
        }

        int estimatedTokens = estimateTokens(result);
        log.info("[Memory] 历史上下文构建完成: sessionId={}, 摘要={}, 短期消息={}条, 估算={}tokens",
                sessionId,
                session != null && session.getSummary() != null ? "有" : "无",
                recentMessages.size(),
                estimatedTokens);

        return result;
    }

    // ======================== 摘要管理 ========================

    /**
     * 异步检查并生成摘要
     *
     * 在每次对话完成后调用。如果消息总数超过 SUMMARY_TRIGGER_COUNT，
     * 异步触发摘要生成/更新，不阻塞主对话流程。
     *
     * @param sessionId 会话ID
     */
    public void maybeGenerateSummaryAsync(String sessionId) {
        Long totalCount = messageMapper.selectCount(
                new LambdaQueryWrapper<AiChatMessage>()
                        .eq(AiChatMessage::getSessionId, sessionId)
                        .in(AiChatMessage::getRole, "user", "assistant")
                        .eq(AiChatMessage::getStatus, "success")
        );

        if (totalCount == null || totalCount < SUMMARY_TRIGGER_COUNT) {
            log.debug("[Memory] 消息数 {} 未达阈值 {}，跳过摘要生成", totalCount, SUMMARY_TRIGGER_COUNT);
            return;
        }

        log.info("[Memory] 消息数 {} 达到阈值，异步触发摘要生成: sessionId={}", totalCount, sessionId);

        streamPool.execute(() -> {
            try {
                generateSummary(sessionId);
            } catch (Exception e) {
                log.warn("[Memory] 异步摘要生成失败: sessionId={}, error={}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * 同步生成/更新会话摘要
     *
     * 核心逻辑：
     * 1. 取"短期窗口之前"的所有旧消息（这些是要被压缩的）
     * 2. 如果已有旧摘要，传给模型让它在此基础上合并新信息
     * 3. 模型生成新摘要 → 更新到 ai_chat_session.summary
     *
     * @param sessionId 会话ID
     * @return 新生成的摘要文本，如果不需要生成则返回 null
     */
    public String generateSummary(String sessionId) {
        // 取所有消息（按时间正序）
        List<AiChatMessage> allMessages = messageMapper.selectList(
                new LambdaQueryWrapper<AiChatMessage>()
                        .eq(AiChatMessage::getSessionId, sessionId)
                        .in(AiChatMessage::getRole, "user", "assistant")
                        .eq(AiChatMessage::getStatus, "success")
                        .orderByAsc(AiChatMessage::getCreateTime)
        );

        if (allMessages.size() <= SHORT_TERM_COUNT) {
            log.debug("[Memory] 消息数 {} 不足短期窗口 {}，不需要摘要", allMessages.size(), SHORT_TERM_COUNT);
            return null;
        }

        // 旧消息 = 全部消息 - 短期窗口（短期窗口内的消息保留原始形态，不压缩）
        List<AiChatMessage> oldMessages = allMessages.subList(0, allMessages.size() - SHORT_TERM_COUNT);
        String conversationText = formatMessagesForPrompt(oldMessages);

        // 读取已有摘要（如果有，让模型合并而非覆盖）
        AiChatSession session = sessionMapper.selectById(sessionId);
        String existingSummary = (session != null && session.getSummary() != null)
                ? session.getSummary() : "";
        String existingHint = existingSummary.isBlank() ? "" :
                "\n已有的旧摘要（请在此基础上合并新信息，不要丢失旧摘要中的关键实体）：\n" + existingSummary + "\n";

        // 构建 Prompt 并调模型
        String prompt = String.format(SUMMARY_PROMPT_TEMPLATE,
                SUMMARY_MAX_LENGTH, existingHint, conversationText);

        long start = System.currentTimeMillis();
        String newSummary = chatModel.chat(prompt);
        long costMs = System.currentTimeMillis() - start;

        if (newSummary == null || newSummary.isBlank()) {
            log.warn("[Memory] 模型返回空摘要: sessionId={}", sessionId);
            return null;
        }

        // 截断保护
        if (newSummary.length() > SUMMARY_MAX_LENGTH) {
            newSummary = newSummary.substring(0, SUMMARY_MAX_LENGTH);
            log.debug("[Memory] 摘要被截断到 {} 字符", SUMMARY_MAX_LENGTH);
        }

        // 更新到数据库
        if (session != null) {
            session.setSummary(newSummary);
            session.setUpdateTime(new Date());
            sessionMapper.updateById(session);
        }

        log.info("[Memory] 摘要生成完成: sessionId={}, 压缩了{}条旧消息, 耗时={}ms, 摘要长度={}字符",
                sessionId, oldMessages.size(), costMs, newSummary.length());

        return newSummary;
    }

    // ======================== 会话结束总结 ========================

    /**
     * 生成会话结束总结（用户手动触发）
     *
     * 与自动摘要的区别：
     * - 自动摘要：压缩旧消息，保留短期窗口原始消息，给模型做上下文用
     * - 结束总结：对全部消息做结构化总结（主题/关键信息/已完成/待跟进），给人看
     *
     * @param sessionId 会话ID
     * @return 结构化总结文本
     */
    public String generateSessionEndSummary(String sessionId) {
        List<AiChatMessage> allMessages = messageMapper.selectList(
                new LambdaQueryWrapper<AiChatMessage>()
                        .eq(AiChatMessage::getSessionId, sessionId)
                        .in(AiChatMessage::getRole, "user", "assistant")
                        .eq(AiChatMessage::getStatus, "success")
                        .orderByAsc(AiChatMessage::getCreateTime)
        );

        if (allMessages.isEmpty()) {
            return "暂无对话内容";
        }

        String conversationText = formatMessagesForPrompt(allMessages);
        String prompt = String.format(SESSION_END_SUMMARY_PROMPT, conversationText);

        long start = System.currentTimeMillis();
        String summary = chatModel.chat(prompt);
        long costMs = System.currentTimeMillis() - start;

        log.info("[Memory] 会话结束总结生成完成: sessionId={}, 消息数={}, 耗时={}ms",
                sessionId, allMessages.size(), costMs);

        // 同时更新到 session.summary（覆盖之前的自动摘要）
        AiChatSession session = sessionMapper.selectById(sessionId);
        if (session != null && summary != null && !summary.isBlank()) {
            session.setSummary(summary);
            session.setUpdateTime(new Date());
            sessionMapper.updateById(session);
        }

        return summary;
    }

    // ======================== 案例管理 ========================

    /**
     * 将会话保存为测试用例
     *
     * 自动从会话消息中提取实际调用的工具列表（通过 toolCalls 字段），
     * 并与用户填写的预期工具列表对比，判断是否符合预期。
     *
     * @param sessionId    会话ID
     * @param caseName     用例名称
     * @param scenario     场景分类
     * @param description  用例描述
     * @param expectedTools 预期调用的工具（逗号分隔）
     * @param userId       当前用户ID
     * @return 创建的用例
     */
    public AiToolChatCase saveAsCase(String sessionId, String caseName, String scenario,
                                     String description, String expectedTools, String userId) {
        // 校验会话存在
        AiChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new RuntimeException("会话不存在: " + sessionId);
        }

        // 从会话消息中自动提取实际调用的工具
        String actualTools = extractActualTools(sessionId);

        // 判断是否符合预期
        Integer isPass = evaluatePass(expectedTools, actualTools);

        // 构建用例实体
        AiToolChatCase chatCase = new AiToolChatCase()
                .setCaseName(caseName)
                .setSessionId(sessionId)
                .setUserId(userId)
                .setScenario(scenario)
                .setDescription(description)
                .setExpectedTools(expectedTools)
                .setActualTools(actualTools)
                .setIsPass(isPass)
                .setCreateBy(userId)
                .setCreateTime(new Date());

        chatCaseService.save(chatCase);

        log.info("[Memory] 会话保存为用例: sessionId={}, caseName={}, 预期工具={}, 实际工具={}, 通过={}",
                sessionId, caseName, expectedTools, actualTools, isPass);

        return chatCase;
    }

    /**
     * 查询用例列表（按创建时间倒序）
     */
    public List<AiToolChatCase> listCases(String scenario) {
        LambdaQueryWrapper<AiToolChatCase> wrapper = new LambdaQueryWrapper<>();
        if (scenario != null && !scenario.isBlank()) {
            wrapper.eq(AiToolChatCase::getScenario, scenario);
        }
        wrapper.orderByDesc(AiToolChatCase::getCreateTime);
        return chatCaseService.list(wrapper);
    }

    // ======================== 内部方法 ========================

    /**
     * 从会话消息中提取实际调用的工具列表
     *
     * 扫描所有 assistant 消息的 toolCalls 字段（JSON 数组），
     * 提取每个 toolCall 的 toolCode，去重后逗号拼接。
     */
    private String extractActualTools(String sessionId) {
        List<AiChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<AiChatMessage>()
                        .eq(AiChatMessage::getSessionId, sessionId)
                        .eq(AiChatMessage::getRole, "assistant")
                        .isNotNull(AiChatMessage::getToolCalls)
                        .ne(AiChatMessage::getToolCalls, "")
                        .orderByAsc(AiChatMessage::getCreateTime)
        );

        Set<String> tools = new LinkedHashSet<>();
        for (AiChatMessage msg : messages) {
            try {
                // toolCalls 是 JSON 数组，每个元素有 toolCode 字段
                List<com.alibaba.fastjson.JSONObject> calls =
                        JSON.parseArray(msg.getToolCalls(), com.alibaba.fastjson.JSONObject.class);
                if (calls != null) {
                    for (com.alibaba.fastjson.JSONObject call : calls) {
                        String toolCode = call.getString("toolCode");
                        if (toolCode != null && !toolCode.isBlank()) {
                            tools.add(toolCode);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[Memory] 解析 toolCalls JSON 失败: msgId={}", msg.getId());
            }
        }

        return tools.isEmpty() ? null : String.join(",", tools);
    }

    /**
     * 评测：预期工具 vs 实际工具是否匹配
     *
     * 判断逻辑：预期工具集合 == 实际工具集合 → 通过（1），否则 → 不通过（0）
     */
    private Integer evaluatePass(String expectedTools, String actualTools) {
        if (expectedTools == null || expectedTools.isBlank()) {
            return null; // 未设置预期，不评测
        }
        if (actualTools == null || actualTools.isBlank()) {
            return 0; // 有预期但没调任何工具 → 不通过
        }

        Set<String> expected = Arrays.stream(expectedTools.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        Set<String> actual = Arrays.stream(actualTools.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        return expected.equals(actual) ? 1 : 0;
    }

    /**
     * 将消息列表格式化成给模型看的文本
     */
    private String formatMessagesForPrompt(List<AiChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (AiChatMessage msg : messages) {
            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.getContent()).append("\n\n");
        }
        String text = sb.toString();
        if (text.length() > HISTORY_TEXT_MAX_LENGTH) {
            text = text.substring(0, HISTORY_TEXT_MAX_LENGTH) + "\n...(内容过长，已截断)";
        }
        return text;
    }

    /**
     * 估算消息列表的 token 数
     *
     * 简易算法：中文字符 × 1.5 + 英文/数字 × 0.75
     * 误差在 ±20% 以内，够做预算控制。
     */
    private int estimateTokens(List<ChatMessage> messages) {
        int tokens = 0;
        for (ChatMessage msg : messages) {
            String text = msg instanceof UserMessage um ? um.singleText() :
                    msg instanceof AiMessage am ? am.text() :
                            msg instanceof SystemMessage sm ? sm.text() : "";
            if (text != null) {
                for (char c : text.toCharArray()) {
                    tokens += (c > 127) ? ZH_TOKEN_RATIO : EN_TOKEN_RATIO;
                }
            }
        }
        return (int) tokens;
    }
}
