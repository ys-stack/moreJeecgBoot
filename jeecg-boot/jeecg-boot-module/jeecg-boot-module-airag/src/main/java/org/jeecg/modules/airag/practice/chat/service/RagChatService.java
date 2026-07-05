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
import org.springframework.util.ObjectUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
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

    @Resource
    private ConversationMemoryService conversationMemoryService;

    private static final String RAG_SYSTEM_PROMPT = """
        你是一个知识库问答助手。请严格根据以下【参考资料】来回答用户的问题。

        输出格式要求：
        1. 使用 Markdown 格式组织回答，合理使用 ## 标题、**加粗**、列表等
        2. 先给出核心结论，再展开详细说明
        3. 涉及多个要点时，使用有序或无序列表
        4. 引用来源时标注，如"根据《xxx》描述..."

        内容要求：
        1. 答案必须基于参考资料，不要编造资料中没有的信息
        2. 如果参考资料信息不足，明确告知用户"参考资料中未找到相关信息，建议补充相关文档到知识库"
        3. 回答要准确、有条理，避免大段堆砌
        """;

    public RagChatService(@Qualifier("practiceChatModel") OpenAiChatModel chatModel,
                          @Qualifier("practiceStreamingChatModel") OpenAiStreamingChatModel streamingChatModel) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
    }

    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 20:13
     * @Desc: 防止会话越权
     */
    private AiChatSession getOwnedSession(String sessionId, String userId) {
        AiChatSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<AiChatSession>()
                        .eq(AiChatSession::getId, sessionId)
                        .eq(AiChatSession::getUserId, userId)
                        .eq(AiChatSession::getStatus, "active")
        );
        if (session == null) {
            throw new RuntimeException("会话不存在或无权访问");
        }
        return session;
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
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            session = createSession(request, userId);
            sessionId = session.getId();
        } else {
            session = getOwnedSession(sessionId, userId);
        }

        // ==================== Step 2: 保存用户消息 ====================
        AiChatMessage userMsg = new AiChatMessage()
                .setSessionId(sessionId)
                .setRole("user")
                .setContent(request.getQuery())
                .setStatus("success")
                .setCreateBy(userId)
                .setCreateTime(new Date());
        messageMapper.insert(userMsg);

        // ==================== Step 2.5: 查询改写（多轮对话时，结合历史生成独立检索词） ====================
        String searchQuery = rewriteQuery(session,request.getQuery());

        // ==================== Step 3: 向量检索（RAG 核心 + 权限过滤） ====================
        // 获取当前用户可访问的知识库ID列表
        List<String> accessibleKbIds = getAccessibleKnowledgeBaseIds(userId);
        List<VectorSearchResultVO> searchResults;
        if (request.getKnowledgeBaseId() != null && !request.getKnowledgeBaseId().isBlank()) {
            // 用户指定了知识库 → 校验权限后使用单知识库搜索
            if (!accessibleKbIds.contains(request.getKnowledgeBaseId())) {
                throw new RuntimeException("无权访问该知识库: " + request.getKnowledgeBaseId());
            }
            searchResults = vectorStoreService.search(searchQuery, topK, request.getKnowledgeBaseId());
        } else {
            // 未指定知识库 → 在所有可访问的知识库中检索
            searchResults = vectorStoreService.searchByKnowledgeBaseIds(searchQuery, topK, accessibleKbIds);
        }
        // 过滤低相关度结果（Rerank 分数通常 0~1，阈值设 0.2~0.3 比较合理）
        double minScore = 0.2;
        List<VectorSearchResultVO> filteredResults = searchResults.stream()
                .filter(r -> r.getScore() >= minScore)
                .collect(Collectors.toList());
        log.info("RAG 向量检索完成: query='{}', 可访问KB数={}, 命中 {} 个 chunk, 过滤后 {} 个",
                request.getQuery(), accessibleKbIds.size(), searchResults.size(), filteredResults.size());

        if (ObjectUtils.isEmpty(filteredResults)) {
            return buildNoReferenceResponse(session, userMsg, userId);
        }

        // ==================== Step 4: 构建 LangChain4j 消息列表 ====================
        List<ChatMessage> messages = buildRagMessages(filteredResults, sessionId, request.getQuery());

        // ==================== Step 5: 调用大模型 ====================
        long startTime = System.currentTimeMillis();
        String ragContextJson = JSON.toJSONString(filteredResults);

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
                    .setSessionId(sessionId)
                    .setParentMessageId(userMsg.getId())
                    .setRole("assistant")
                    .setContent(answer)
                    .setPromptTokens(promptTokens)
                    .setCompletionTokens(completionTokens)
                    .setTotalTokens(promptTokens != null && completionTokens != null ? promptTokens + completionTokens : 0)
                    .setRagContext(ragContextJson)
                    .setRagChunkCount(filteredResults.size())
                    .setModelProvider("openai")
                    .setModelName(modelName)
                    .setDurationMs(durationMs)
                    .setStatus("success")
                    .setCreateBy(userId)
                    .setCreateTime(new Date());
            messageMapper.insert(assistantMsg);

            // 异步检查是否需要生成/更新摘要（消息数超阈值时在后台线程池触发，不阻塞当前请求）
            conversationMemoryService.maybeGenerateSummaryAsync(session.getId());

            // ==================== Step 7: 更新会话（首条消息自动生成标题） ====================
            updateSessionAfterChat(session, userMsg.getId());

            // ==================== Step 8: 构建响应 ====================
            return RagChatResponse.builder()
                    .sessionId(session.getId())
                    .userMessageId(userMsg.getId())
                    .assistantMessageId(assistantMsg.getId())
                    .answer(answer)
                    .references(RagChatResponse.fromSearchResults(filteredResults))
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
                    .setRagChunkCount(filteredResults.size())
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

    /*
     * @Author: ys
     * @Date: 2026/7/2 11:21
     * @DESC: 多轮对话时，结合历史生成独立检索词
     */
    private String rewriteQuery(AiChatSession session, String query) {
        List<AiChatMessage> aiChatMessages = messageMapper.loadRecentMessages(session.getId(), 6);
        if (ObjectUtils.isEmpty(aiChatMessages)) {
            return query;
        }
        // 构建改写 Prompt
        StringBuilder historyText = new StringBuilder();
        for (AiChatMessage msg : aiChatMessages) {
            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            historyText.append(role).append(": ").append(msg.getContent()).append("\n");
        }

        String prompt = String.format("""
            你是一个搜索查询改写助手。根据对话历史，将用户的最新问题改写为一个独立的、适合在知识库中检索的查询。
            
            要求：
            1. 解析指代词（它、这个、那个、刚才提到的等），替换为具体实体
            2. 补充隐含上下文，使查询自包含
            3. 保持简洁，不要超过50个字
            4. 如果问题本身已经足够明确，不需要改写，直接返回原问题
            
            对话历史：
            %s
            
            用户最新问题：%s
            
            请直接输出改写后的查询，不要加任何前缀或解释。
            """, historyText, query);

        try{
            String rewritten = chatModel.chat(prompt);
            if (rewritten != null && !rewritten.isBlank()) {
                return rewritten.trim();
            }
        }catch (Exception e){
            log.warn("查询改写失败，使用原始查询: {}", e.getMessage());
        }
        return query;
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
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            session = createSession(request, userId);
        } else {
            try{
                session = getOwnedSession(sessionId, userId);
            }catch (Exception e){
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    emitter.complete();
                } catch (IOException sendError) {
                    emitter.completeWithError(sendError);
                }
                return emitter;
            }
        }
        sessionId = session.getId();

        // Step 2: 保存用户消息
        AiChatMessage userMsg = new AiChatMessage()
                .setSessionId(sessionId)
                .setRole("user")
                .setContent(request.getQuery())
                .setStatus("success")
                .setCreateBy(userId)
                .setCreateTime(new Date());
        messageMapper.insert(userMsg);

        // ==================== Step 2.5: 查询改写（多轮对话时，结合历史生成独立检索词） ====================
        String searchQuery = rewriteQuery(session,request.getQuery());

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
            searchResults = vectorStoreService.search(searchQuery, topK, request.getKnowledgeBaseId());
        } else {
            searchResults = vectorStoreService.searchByKnowledgeBaseIds(searchQuery, topK, accessibleKbIds);
        }
        log.info("[{}] RAG 流式检索完成: 可访问KB={}, 命中chunk={}", requestId, accessibleKbIds.size(), searchResults.size());

        // 过滤低相关度结果（Rerank 分数通常 0~1，阈值 0.2）
        double minScore = 0.2;
        List<VectorSearchResultVO> filteredResults = searchResults.stream()
                .filter(r -> r.getScore() >= minScore)
                .collect(Collectors.toList());
        log.info("[{}] 分数过滤: 原始={}, 过滤后={}, 阈值={}", requestId, searchResults.size(), filteredResults.size(), minScore);

        if (ObjectUtils.isEmpty(filteredResults)) {
            RagChatResponse response = buildNoReferenceResponse(session, userMsg, userId);
            try {
                emitter.send(SseEmitter.event().name("meta").data(JSON.toJSONString(response)));
                emitter.send(SseEmitter.event().name("message").data(response.getAnswer()));
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (IOException e) {
                log.warn("[{}] 发送无引用响应失败", requestId, e);
                emitter.completeWithError(e);
            }
            return emitter;
        }
        // Step 4: 构建消息列表（使用过滤后的结果）
        List<ChatMessage> messages = buildRagMessages(filteredResults, session.getId(), request.getQuery());
        String ragContextJson = JSON.toJSONString(filteredResults);

        // 发送 meta 事件（sessionId + 参考来源），前端据此更新 UI
        try {
            RagChatResponse metaResp = RagChatResponse.builder()
                    .sessionId(session.getId())
                    .userMessageId(userMsg.getId())
                    .references(RagChatResponse.fromSearchResults(filteredResults))
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
                                .setRagChunkCount(filteredResults.size())
                                .setModelProvider("openai")
                                .setModelName(modelName)
                                .setDurationMs(costMs)
                                .setStatus("success")
                                .setCreateBy(userId)
                                .setCreateTime(new Date());
                        messageMapper.insert(assistantMsg);
                        // 异步检查是否需要生成/更新摘要（非关键操作，失败不影响主流程）
                        try {
                            conversationMemoryService.maybeGenerateSummaryAsync(session.getId());
                            updateSessionAfterChat(session, userMsg.getId());
                        } catch (Exception ex) {
                            log.warn("[{}] 摘要/会话更新失败（不影响主流程）", requestId, ex);
                        }

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
                                .setRagChunkCount(filteredResults.size())
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
                long costMs = System.currentTimeMillis() - startTime;
                log.error("[{}] 流式调用启动失败", requestId, e);

                // 补存一条 assistant error 消息（用户消息已存，不能没有对应的 assistant 记录）
                try {
                    AiChatMessage errorMsg = new AiChatMessage()
                            .setSessionId(session.getId())
                            .setParentMessageId(userMsg.getId())
                            .setRole("assistant")
                            .setContent("模型调用失败: " + e.getMessage())
                            .setModelName(modelName)
                            .setDurationMs(costMs)
                            .setStatus("error")
                            .setErrorMsg(e.getMessage())
                            .setCreateBy(userId)
                            .setCreateTime(new Date());
                    messageMapper.insert(errorMsg);
                } catch (Exception ex) {
                    log.error("[{}] 保存 error 消息也失败", requestId, ex);
                }

                try {
                    emitter.send(SseEmitter.event().name("error").data("模型调用启动失败: " + e.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 21:26
     * @Desc: 构建拒答消息
     */
    private RagChatResponse buildNoReferenceResponse(AiChatSession session, AiChatMessage userMsg, String userId) {
        String answer = "知识库中未找到相关信息，建议补充相关文档到知识库";

        AiChatMessage assistantMsg = new AiChatMessage()
                .setSessionId(session.getId())
                .setParentMessageId(userMsg.getId())
                .setRole("assistant")
                .setContent(answer)
                .setRagContext("[]")
                .setRagChunkCount(0)
                .setModelProvider("openai")
                .setModelName(modelName)
                .setDurationMs(0L)
                .setStatus("success")
                .setCreateBy(userId)
                .setCreateTime(new Date());

        messageMapper.insert(assistantMsg);
        updateSessionAfterChat(session, userMsg.getId());

        return RagChatResponse.builder()
                .sessionId(session.getId())
                .userMessageId(userMsg.getId())
                .assistantMessageId(assistantMsg.getId())
                .answer(answer)
                .references(RagChatResponse.fromSearchResults(Collections.emptyList()))
                .model(modelName)
                .durationMs(0L)
                .build();
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
    public List<AiChatMessage> listMessages(String sessionId,String userId) {
        getOwnedSession(sessionId, userId);
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

        // 2. 历史对话（滑动窗口 + 摘要压缩，由 ConversationMemoryService 统一管理）
        //    返回结构：[SystemMessage: 摘要(如果有)] + [最近N条 UserMessage/AiMessage]
        List<ChatMessage> historyMessages = conversationMemoryService.buildHistoryMessages(sessionId);
        messages.addAll(historyMessages);

        // 3. 当前用户问题
        messages.add(new UserMessage(currentQuery));

        log.debug("RAG 消息列表构建完成: systemPrompt长度={}, 历史消息{}条, 总消息{}条",systemPrompt.length(), historyMessages.size(), messages.size());
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
}
