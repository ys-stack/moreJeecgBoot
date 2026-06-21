package org.jeecg.modules.airag.practice.chat.service;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.chat.entity.AiChatMessage;
import org.jeecg.modules.airag.practice.chat.entity.AiChatSession;
import org.jeecg.modules.airag.practice.chat.mapper.AiChatMessageMapper;
import org.jeecg.modules.airag.practice.chat.mapper.AiChatSessionMapper;
import org.jeecg.modules.airag.practice.chat.vo.RagChatRequest;
import org.jeecg.modules.airag.practice.chat.vo.RagChatResponse;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;
import org.jeecg.modules.airag.practice.doc.service.IAiKnowledgeBaseService;
import org.jeecg.modules.airag.practice.threadpool.PracticeThreadPool;
import org.jeecg.modules.airag.practice.vector.service.VectorStoreService;
import org.jeecg.modules.airag.practice.vector.vo.VectorSearchResultVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RAG 聊天服务
 *
 * 完整流程：
 * 1. 用户提问 → 保存 user message
 * 2. 向量检索 → 找到知识库中最相关的 chunk
 * 3. 构建 Prompt → 系统指令 + 检索到的上下文 + 历史对话 + 用户问题
 * 4. 调用大模型 → 获取回答
 * 5. 保存 assistant message（含 RAG 上下文）
 * 6. 返回回答 + 参考来源
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-20
 */
@Slf4j
@Service
public class RagChatService {

    private final OpenAiChatModel chatModel;
    private final OpenAiStreamingChatModel streamingChatModel;

    @Resource
    @Qualifier("practiceStreamPool")
    private PracticeThreadPool streamPool;

    @Value("${practice.ai.model-name:deepseek-chat}")
    private String modelName;

    @Resource
    private VectorStoreService vectorStoreService;

    @Resource
    private AiChatSessionMapper sessionMapper;

    @Resource
    private AiChatMessageMapper messageMapper;

    @Resource
    private IAiKnowledgeBaseService knowledgeBaseService;

    /** RAG 系统提示词：约束模型基于检索到的上下文回答 */
    private static final String RAG_SYSTEM_PROMPT = """
            你是一个知识库问答助手。请严格根据以下【参考资料】来回答用户的问题。

            要求：
            1. 答案必须基于参考资料中的内容，不要编造参考资料中没有的信息
            2. 尽量引用来源，例如"根据《xxx》文档中的描述..."
            3. 如果参考资料中没有足够的信息来回答问题，请明确告知用户"参考资料中未找到相关信息，建议补充相关文档到知识库"
            4. 回答要准确、简洁、有条理
            """;

    public RagChatService(@Qualifier("practiceChatModel") OpenAiChatModel chatModel,
                          @Qualifier("practiceStreamingChatModel") OpenAiStreamingChatModel streamingChatModel) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
    }

    // ==================== 核心 RAG 流程 ====================

    /**
     * RAG 聊天：用户提问 → 向量检索 → 构建 Prompt → 调模型 → 返回答案
     *
     * @param request 聊天请求（含 query、sessionId、knowledgeBaseId、topK）
     * @param userId  当前用户ID（从 Shiro 获取，practice 模块暂用固定值）
     * @return RAG 响应（含 AI 回答 + 参考来源 + token 统计）
     */
    @Transactional(rollbackFor = Exception.class)
    public RagChatResponse ragChat(RagChatRequest request, String userId) {
        int topK = request.getTopK() != null ? request.getTopK() : 5;

        // ==================== Step 1: 会话管理 ====================
        // sessionId 为空 → 首次对话，自动创建会话
        AiChatSession session;
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            session = createSession(request, userId);
        } else {
            session = sessionMapper.selectById(request.getSessionId());
            if (session == null) {
                throw new RuntimeException("会话不存在: " + request.getSessionId());
            }
        }

        // ==================== Step 2: 保存用户消息 ====================
        AiChatMessage userMsg = new AiChatMessage()
                .setSessionId(session.getId())
                .setRole("user")
                .setContent(request.getQuery())
                .setStatus("success")
                .setCreateBy(userId)
                .setCreateTime(new Date());
        messageMapper.insert(userMsg);

        // ==================== Step 3: 向量检索（RAG 核心 + 权限过滤） ====================
        // 获取当前用户可访问的知识库ID列表
        List<String> accessibleKbIds = getAccessibleKnowledgeBaseIds(userId);
        List<VectorSearchResultVO> searchResults;
        if (request.getKnowledgeBaseId() != null && !request.getKnowledgeBaseId().isBlank()) {
            // 用户指定了知识库 → 校验权限后使用单知识库搜索
            if (!accessibleKbIds.contains(request.getKnowledgeBaseId())) {
                throw new RuntimeException("无权访问该知识库: " + request.getKnowledgeBaseId());
            }
            searchResults = vectorStoreService.search(request.getQuery(), topK, request.getKnowledgeBaseId());
        } else {
            // 未指定知识库 → 在所有可访问的知识库中检索
            searchResults = vectorStoreService.searchByKnowledgeBaseIds(
                    request.getQuery(), topK, accessibleKbIds);
        }
        log.info("RAG 向量检索完成: query='{}', 可访问KB数={}, 命中 {} 个 chunk",
                request.getQuery(), accessibleKbIds.size(), searchResults.size());

        // ==================== Step 4: 构建 LangChain4j 消息列表 ====================
        List<ChatMessage> messages = buildRagMessages(searchResults, session.getId(), request.getQuery());

        // ==================== Step 5: 调用大模型 ====================
        long startTime = System.currentTimeMillis();
        String ragContextJson = JSON.toJSONString(searchResults);

        try {
            ChatResponse chatResponse = chatModel.chat(messages);
            long durationMs = System.currentTimeMillis() - startTime;
            String answer = chatResponse.aiMessage().text();

            // 提取 token 用量
            Integer promptTokens = null;
            Integer completionTokens = null;
            if (chatResponse.tokenUsage() != null) {
                promptTokens = chatResponse.tokenUsage().inputTokenCount();
                completionTokens = chatResponse.tokenUsage().outputTokenCount();
            }

            log.info("RAG 模型调用成功: 耗时={}ms, tokens={}/{}", durationMs, promptTokens, completionTokens);

            // ==================== Step 6: 保存 AI 回答消息 ====================
            AiChatMessage assistantMsg = new AiChatMessage()
                    .setSessionId(session.getId())
                    .setParentMessageId(userMsg.getId())
                    .setRole("assistant")
                    .setContent(answer)
                    .setPromptTokens(promptTokens)
                    .setCompletionTokens(completionTokens)
                    .setTotalTokens(promptTokens != null && completionTokens != null
                            ? promptTokens + completionTokens : 0)
                    .setRagContext(ragContextJson)
                    .setRagChunkCount(searchResults.size())
                    .setModelProvider("openai")
                    .setModelName(modelName)
                    .setDurationMs(durationMs)
                    .setStatus("success")
                    .setCreateBy(userId)
                    .setCreateTime(new Date());
            messageMapper.insert(assistantMsg);

            // ==================== Step 7: 更新会话（首条消息自动生成标题） ====================
            updateSessionAfterChat(session, userMsg.getId());

            // ==================== Step 8: 构建响应 ====================
            return RagChatResponse.builder()
                    .sessionId(session.getId())
                    .userMessageId(userMsg.getId())
                    .assistantMessageId(assistantMsg.getId())
                    .answer(answer)
                    .references(RagChatResponse.fromSearchResults(searchResults))
                    .model(modelName)
                    .durationMs(durationMs)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .build();

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("RAG 模型调用失败: {}", e.getMessage(), e);

            // 保存失败的 AI 消息记录
            AiChatMessage errorMsg = new AiChatMessage()
                    .setSessionId(session.getId())
                    .setParentMessageId(userMsg.getId())
                    .setRole("assistant")
                    .setContent("模型调用失败: " + e.getMessage())
                    .setRagContext(ragContextJson)
                    .setRagChunkCount(searchResults.size())
                    .setModelName(modelName)
                    .setDurationMs(durationMs)
                    .setStatus("error")
                    .setErrorMsg(e.getMessage())
                    .setCreateBy(userId)
                    .setCreateTime(new Date());
            messageMapper.insert(errorMsg);

            throw new RuntimeException("RAG 模型调用失败: " + e.getMessage(), e);
        }
    }

    // ==================== 流式 RAG 聊天（SSE） ====================

    /**
     * RAG 流式聊天：前置步骤同步执行（会话管理 → 保存用户消息 → 向量检索 → 构建 Prompt），
     * 模型调用异步流式返回。
     *
     * SSE 事件协议：
     *   event: meta    → JSON {sessionId, references[]}（首条事件，前端据此更新会话）
     *   event: message → 每个 token 文本片段
     *   event: done    → 空字符串（流结束）
     *   event: error   → 错误信息文本
     */
    public SseEmitter ragChatStream(RagChatRequest request, String userId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int topK = request.getTopK() != null ? request.getTopK() : 5;

        // ===== 前置步骤（同步，快速返回错误给前端） =====
        // Step 1: 会话管理
        AiChatSession session;
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            session = createSession(request, userId);
        } else {
            session = sessionMapper.selectById(request.getSessionId());
            if (session == null) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("会话不存在: " + request.getSessionId()));
                    emitter.complete();
                } catch (Exception ignored) {}
                return emitter;
            }
        }

        // Step 2: 保存用户消息
        AiChatMessage userMsg = new AiChatMessage()
                .setSessionId(session.getId())
                .setRole("user")
                .setContent(request.getQuery())
                .setStatus("success")
                .setCreateBy(userId)
                .setCreateTime(new Date());
        messageMapper.insert(userMsg);

        // Step 3: 向量检索（权限过滤）
        List<String> accessibleKbIds = getAccessibleKnowledgeBaseIds(userId);
        List<VectorSearchResultVO> searchResults;
        if (request.getKnowledgeBaseId() != null && !request.getKnowledgeBaseId().isBlank()) {
            if (!accessibleKbIds.contains(request.getKnowledgeBaseId())) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("无权访问该知识库"));
                    emitter.complete();
                } catch (Exception ignored) {}
                return emitter;
            }
            searchResults = vectorStoreService.search(request.getQuery(), topK, request.getKnowledgeBaseId());
        } else {
            searchResults = vectorStoreService.searchByKnowledgeBaseIds(request.getQuery(), topK, accessibleKbIds);
        }
        log.info("[{}] RAG 流式检索完成: 可访问KB={}, 命中chunk={}", requestId, accessibleKbIds.size(), searchResults.size());

        // Step 4: 构建消息列表
        List<ChatMessage> messages = buildRagMessages(searchResults, session.getId(), request.getQuery());
        String ragContextJson = JSON.toJSONString(searchResults);

        // 发送 meta 事件（sessionId + 参考来源），前端据此更新 UI
        try {
            RagChatResponse metaResp = RagChatResponse.builder()
                    .sessionId(session.getId())
                    .userMessageId(userMsg.getId())
                    .references(RagChatResponse.fromSearchResults(searchResults))
                    .model(modelName)
                    .build();
            emitter.send(SseEmitter.event().name("meta").data(JSON.toJSONString(metaResp)));
        } catch (Exception e) {
            log.warn("[{}] 发送 meta 事件失败", requestId, e);
            emitter.completeWithError(e);
            return emitter;
        }

        // ===== 异步流式调用模型 =====
        List<String> answerTokens = new java.util.concurrent.CopyOnWriteArrayList<>();
        long startTime = System.currentTimeMillis();

        streamPool.execute(() -> {
            try {
                streamingChatModel.chat(messages, new StreamingChatResponseHandler() {

                    @Override
                    public void onPartialResponse(String token) {
                        answerTokens.add(token);
                        try {
                            emitter.send(SseEmitter.event().name("message").data(token));
                        } catch (Exception e) {
                            log.warn("[{}] SSE 发送失败: {}", requestId, e.getMessage());
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        long costMs = System.currentTimeMillis() - startTime;
                        String fullAnswer = String.join("", answerTokens);

                        // 提取 token 用量
                        Integer promptTokens = null;
                        Integer completionTokens = null;
                        if (response != null && response.tokenUsage() != null) {
                            promptTokens = response.tokenUsage().inputTokenCount();
                            completionTokens = response.tokenUsage().outputTokenCount();
                        }

                        log.info("[{}] RAG 流式完成: 耗时={}ms, tokens={}/{}", requestId, costMs, promptTokens, completionTokens);

                        // 保存 AI 回答消息
                        AiChatMessage assistantMsg = new AiChatMessage()
                                .setSessionId(session.getId())
                                .setParentMessageId(userMsg.getId())
                                .setRole("assistant")
                                .setContent(fullAnswer)
                                .setPromptTokens(promptTokens)
                                .setCompletionTokens(completionTokens)
                                .setTotalTokens(promptTokens != null && completionTokens != null
                                        ? promptTokens + completionTokens : 0)
                                .setRagContext(ragContextJson)
                                .setRagChunkCount(searchResults.size())
                                .setModelProvider("openai")
                                .setModelName(modelName)
                                .setDurationMs(costMs)
                                .setStatus("success")
                                .setCreateBy(userId)
                                .setCreateTime(new Date());
                        messageMapper.insert(assistantMsg);

                        // 更新会话
                        updateSessionAfterChat(session, userMsg.getId());

                        try {
                            emitter.send(SseEmitter.event().name("done").data(""));
                            emitter.complete();
                        } catch (Exception e) {
                            log.warn("[{}] SSE complete 失败", requestId, e);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        long costMs = System.currentTimeMillis() - startTime;
                        log.error("[{}] RAG 流式异常: {}", requestId, error.getMessage(), error);

                        // 保存失败记录
                        AiChatMessage errorMsg = new AiChatMessage()
                                .setSessionId(session.getId())
                                .setParentMessageId(userMsg.getId())
                                .setRole("assistant")
                                .setContent("模型调用失败: " + error.getMessage())
                                .setRagContext(ragContextJson)
                                .setRagChunkCount(searchResults.size())
                                .setModelName(modelName)
                                .setDurationMs(costMs)
                                .setStatus("error")
                                .setErrorMsg(error.getMessage())
                                .setCreateBy(userId)
                                .setCreateTime(new Date());
                        messageMapper.insert(errorMsg);

                        try {
                            emitter.send(SseEmitter.event().name("error")
                                    .data("模型调用异常: " + error.getMessage()));
                        } catch (Exception ignored) {}
                        emitter.completeWithError(error);
                    }
                });
            } catch (Exception e) {
                log.error("[{}] 流式调用启动失败", requestId, e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    // ==================== 会话 & 消息查询 ====================

    /**
     * 查询用户的会话列表（按创建时间倒序）
     */
    public List<AiChatSession> listSessions(String userId) {
        LambdaQueryWrapper<AiChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiChatSession::getUserId, userId)
                .eq(AiChatSession::getStatus, "active")
                .orderByDesc(AiChatSession::getCreateTime);
        return sessionMapper.selectList(wrapper);
    }

    /**
     * 查询某个会话的所有消息（按创建时间正序）
     */
    public List<AiChatMessage> listMessages(String sessionId) {
        LambdaQueryWrapper<AiChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiChatMessage::getSessionId, sessionId)
                .orderByAsc(AiChatMessage::getCreateTime);
        return messageMapper.selectList(wrapper);
    }

    // ==================== 内部方法 ====================

    /**
     * 构建 RAG 消息列表：SystemPrompt(含上下文) + 历史对话 + 当前问题
     *
     * 消息结构：
     * [SystemMessage: RAG指令 + 检索到的chunk作为参考资料]
     * [UserMessage:   历史第1轮用户问题]
     * [AiMessage:     历史第1轮AI回答]
     * [UserMessage:   历史第2轮用户问题]
     * [AiMessage:     历史第2轮AI回答]
     * ...
     * [UserMessage:   当前用户问题]
     */
    private List<ChatMessage> buildRagMessages(List<VectorSearchResultVO> searchResults,
                                                String sessionId,
                                                String currentQuery) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. System Message：RAG 指令 + 检索到的参考资料
        String contextText = buildContextText(searchResults);
        String systemPrompt = RAG_SYSTEM_PROMPT + "\n\n【参考资料】\n" + contextText;
        messages.add(new SystemMessage(systemPrompt));

        // 2. 历史对话（用于多轮对话上下文，最多取最近5条避免超出 token 限制）
        List<AiChatMessage> history = listRecentMessages(sessionId, 5);
        for (AiChatMessage histMsg : history) {
            if ("user".equals(histMsg.getRole())) {
                messages.add(new UserMessage(histMsg.getContent()));
            } else if ("assistant".equals(histMsg.getRole()) && "success".equals(histMsg.getStatus())) {
                messages.add(new AiMessage(histMsg.getContent()));
            }
        }

        // 3. 当前用户问题
        messages.add(new UserMessage(currentQuery));

        log.debug("RAG 消息列表构建完成: systemPrompt长度={}, 历史消息{}条, 总消息{}条",systemPrompt.length(), history.size(), messages.size());
        return messages;
    }


    /**
     * 将检索到的 chunk 拼接成参考资料文本
     *
     * 格式：
     * [来源1] 文件: xxx.md | 标题: xxx | 相关度: 0.85
     * 内容...
     *
     * [来源2] ...
     */
    private String buildContextText(List<VectorSearchResultVO> results) {
        if (results == null || results.isEmpty()) {
            return "（未检索到相关参考资料）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            VectorSearchResultVO r = results.get(i);
            sb.append(String.format("[来源%d] 文件: %s | 标题: %s | 相关度: %.2f%n",
                    i + 1,
                    r.getSourceFileName() != null ? r.getSourceFileName() : "未知",
                    r.getHeadingPath() != null ? r.getHeadingPath() : "无",
                    r.getScore()));
            sb.append(r.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 创建新会话
     */
    private AiChatSession createSession(RagChatRequest request, String userId) {
        AiChatSession session = new AiChatSession()
                .setTitle(request.getQuery().length() > 50
                        ? request.getQuery().substring(0, 50) + "..."
                        : request.getQuery())
                .setUserId(userId)
                .setKnowledgeBaseId(request.getKnowledgeBaseId())
                .setModelProvider("openai")
                .setModelName(modelName)
                .setStatus("active")
                .setMessageCount(0)
                .setCreateBy(userId)
                .setCreateTime(new Date());
        sessionMapper.insert(session);
        log.info("RAG 会话创建成功: id={}, title='{}'", session.getId(), session.getTitle());
        return session;
    }

    /**
     * 聊天完成后更新会话：
     * - 增加消息计数（user + assistant = +2）
     * - 首条对话时用用户问题作为标题
     */
    private void updateSessionAfterChat(AiChatSession session, String userMsgId) {
        int newCount = (session.getMessageCount() != null ? session.getMessageCount() : 0) + 2;
        session.setMessageCount(newCount);
        session.setUpdateBy(session.getCreateBy());
        session.setUpdateTime(new Date());
        sessionMapper.updateById(session);
    }

    /**
     * 获取当前用户可访问的知识库ID列表（基于角色权限过滤）
     */
    public List<String> getAccessibleKnowledgeBaseIds(String userId) {
        // 从 Shiro 获取用户角色
        LoginUser loginUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        List<String> roleCodes = new ArrayList<>();
        if (loginUser != null && loginUser.getRoleCode() != null && !loginUser.getRoleCode().isBlank()) {
            roleCodes = Arrays.stream(loginUser.getRoleCode().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        // 查询用户可访问的知识库
        List<AiKnowledgeBase> accessibleKbs = knowledgeBaseService.listAccessibleByUser(roleCodes);
        return accessibleKbs.stream()
                .map(AiKnowledgeBase::getId)
                .collect(Collectors.toList());
    }

    /**
     * 获取当前用户可访问的知识库列表（供 Controller 调用）
     */
    public List<AiKnowledgeBase> listAccessibleKnowledgeBases() {
        LoginUser loginUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        List<String> roleCodes = new ArrayList<>();
        if (loginUser != null && loginUser.getRoleCode() != null && !loginUser.getRoleCode().isBlank()) {
            roleCodes = Arrays.stream(loginUser.getRoleCode().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        return knowledgeBaseService.listAccessibleByUser(roleCodes);
    }

    /**
     * 获取最近 N 条历史消息（按时间倒序取 N 条，再按时间正序返回）
     */
    private List<AiChatMessage> listRecentMessages(String sessionId, int limit) {
        LambdaQueryWrapper<AiChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiChatMessage::getSessionId, sessionId)
                .in(AiChatMessage::getRole, "user", "assistant")
                .orderByDesc(AiChatMessage::getCreateTime)
                .last("LIMIT " + limit);
        List<AiChatMessage> recent = messageMapper.selectList(wrapper);
        // 反转为正序（最早的在前）
        java.util.Collections.reverse(recent);
        return recent;
    }
}
