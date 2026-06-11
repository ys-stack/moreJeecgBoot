package org.jeecg.modules.airag.practice.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.vo.PracticeChatRequest;
import org.jeecg.modules.airag.practice.vo.PracticeChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 练习用 - AI 聊天服务实现
 *
 * 这个类展示了 LangChain4j 调用大模型的基本方式。
 * 对照着看：
 *   - chat() 是最基础的同步调用
 *   - chatStream() 是流式调用（SSE）
 *   - chatStructured() 是结构化输出（通过 Prompt 约束模型返回 JSON）
 */
@Slf4j
@Service
public class PracticeChatServiceImpl implements IPracticeChatService {

    private final OpenAiChatModel chatModel;
    private final OpenAiStreamingChatModel streamingChatModel;

    @Value("${practice.ai.model-name:deepseek-chat}")
    private String modelName;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public PracticeChatServiceImpl(
            @Qualifier("practiceChatModel") OpenAiChatModel chatModel,
            @Qualifier("practiceStreamingChatModel") OpenAiStreamingChatModel streamingChatModel) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
    }

    /**
     * 普通聊天 - 同步调用
     *
     * 核心就三步：
     * 1. 构建消息列表（system + user）
     * 2. 调用 chatModel.chat(messages)
     * 3. 从 ChatResponse 中取出 AiMessage 文本
     */
    @Override
    public PracticeChatResponse chat(PracticeChatRequest request) {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long startTime = System.currentTimeMillis();

        // 1. 构建消息列表
        List<ChatMessage> messages = buildMessages(request);

        // 2. 调用模型（chat 是 LangChain4j 1.x 的同步方法）
        try {
            ChatResponse chatResponse = chatModel.chat(messages);
            long costMs = System.currentTimeMillis() - startTime;
            String content = chatResponse.aiMessage().text();

            log.info("[{}] 模型调用成功 | 模型={} | 耗时={}ms | 回答长度={}",
                    requestId, modelName, costMs, content.length());

            return PracticeChatResponse.builder()
                    .content(content)
                    .model(modelName)
                    .costMs(costMs)
                    .requestId(requestId)
                    .build();
        } catch (Exception e) {
            log.error("[{}] 模型调用失败: {}", requestId, e.getMessage(), e);
            long costMs = System.currentTimeMillis() - startTime;
            return PracticeChatResponse.builder()
                    .content("模型调用失败: " + e.getMessage())
                    .model(modelName)
                    .costMs(costMs)
                    .requestId(requestId)
                    .build();
        }
    }

    /**
     * 流式聊天 - SSE 逐字返回
     *
     * 流式输出对用户体验很重要：用户不需要等模型生成完才看到内容。
     * 这里用 LangChain4j 的 StreamingResponseHandler + Spring 的 SseEmitter 实现。
     *
     * 注意：OpenAiStreamingChatModel 的 generate 方法接收 StreamingResponseHandler 回调，
     * 而不是直接返回 TokenStream（TokenStream 是 AiServices 高级 API 的产物）。
     */
    @Override
    public SseEmitter chatStream(PracticeChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L); // 120秒超时
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        List<ChatMessage> messages = buildMessages(request);

        // 用异步线程发送，避免阻塞主线程
        executor.execute(() -> {
            try {
                long startTime = System.currentTimeMillis();

                // chat + StreamingChatResponseHandler 是 LangChain4j 1.x 的流式调用方式
                streamingChatModel.chat(messages, new StreamingChatResponseHandler() {

                    @Override
                    public void onPartialResponse(String token) {
                        // 每收到一个 token 片段就通过 SSE 推送给前端
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(token));
                        } catch (Exception e) {
                            log.warn("[{}] SSE 发送失败: {}", requestId, e.getMessage());
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        try {
                            long costMs = System.currentTimeMillis() - startTime;
                            log.info("[{}] 流式调用完成 | 耗时={}ms", requestId, costMs);
                            // 发送结束标记
                            emitter.send(SseEmitter.event().name("done").data(""));
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("[{}] 流式调用异常: {}", requestId, error.getMessage(), error);
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

    /**
     * 结构化输出 - 通过 Prompt 约束模型返回 JSON
     *
     * 这是 AI 应用开发中非常重要的技巧：
     * 不是让模型随便回答，而是要求它按指定 JSON Schema 输出，
     * 这样程序可以直接解析和使用模型输出。
     */
    @Override
    public PracticeChatResponse chatStructured(PracticeChatRequest request) {
        String structuredSystemPrompt = """
                你是一个需求分析助手。用户会输入一段需求描述，请你按以下 JSON 格式输出分析结果：
                
                ```json
                {
                  "background": "需求背景分析",
                  "goal": "核心目标",
                  "apis": [
                    {"method": "GET/POST", "path": "/api/xxx", "description": "接口说明"}
                  ],
                  "tables": [
                    {"tableName": "表名", "description": "说明", "keyFields": ["字段1", "字段2"]}
                  ],
                  "risks": ["风险点1", "风险点2"]
                }
                ```
                
                注意：
                1. 必须严格输出 JSON，不要有多余的文字
                2. APIs 和 tables 至少各一个
                3. risks 至少列出一个风险点
                """;

        PracticeChatRequest structuredRequest = new PracticeChatRequest();
        structuredRequest.setMessage(request.getMessage());
        structuredRequest.setSystemPrompt(structuredSystemPrompt);

        return chat(structuredRequest);
    }

    /**
     * 构建消息列表（公共方法，三个接口都用）
     */
    private List<ChatMessage> buildMessages(PracticeChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.getSystemPrompt()));
        }
        messages.add(new UserMessage(request.getMessage()));
        return messages;
    }
}
