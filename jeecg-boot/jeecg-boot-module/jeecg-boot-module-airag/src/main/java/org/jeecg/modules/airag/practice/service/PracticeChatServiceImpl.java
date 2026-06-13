package org.jeecg.modules.airag.practice.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.aspect.annotation.ModelInvocationLog;
import org.jeecg.modules.airag.practice.aspect.annotation.RateLimit;
import org.jeecg.modules.airag.practice.log.entity.AiModelCallLog;
import org.jeecg.modules.airag.practice.log.service.IAiModelCallLogService;
import org.jeecg.modules.airag.practice.prompt.service.IAiPromptTemplateService;
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
 *   - chat() 是最基础的同步调用（支持模板）
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

    @Resource
    private IAiPromptTemplateService iAiPromptTemplateService;

    @Resource
    private IAiModelCallLogService aiModelCallLogService;

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
     * 构建 system prompt 的优先级：
     * 1. promptCode 有值 → 从数据库加载模板并渲染变量
     * 2. systemPrompt 有值 → 直接用传入的文本
     * 3. 都没有 → 不设 system message，直接发用户问题
     */
    @ModelInvocationLog(async = false)
    @RateLimit(key = "practice:chat")
    @Override
    public PracticeChatResponse chat(PracticeChatRequest request) {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long startTime = System.currentTimeMillis();

        List<ChatMessage> messages = buildMessages(request);

        try {
            ChatResponse chatResponse = chatModel.chat(messages);
            long costMs = System.currentTimeMillis() - startTime;
            String content = chatResponse.aiMessage().text();

            // 从模型返回的 usage 中提取真实 token 数（非估算）
            Integer promptTokens = null;
            Integer completionTokens = null;
            if (chatResponse.tokenUsage() != null) {
                promptTokens = chatResponse.tokenUsage().inputTokenCount();
                completionTokens = chatResponse.tokenUsage().outputTokenCount();
            }

            log.info("[{}] 模型调用成功 | 模型={} | 耗时={}ms | tokens={}/{} ",
                    requestId, modelName, costMs, promptTokens, completionTokens);

            return PracticeChatResponse.builder()
                    .content(content)
                    .model(modelName)
                    .costMs(costMs)
                    .requestId(requestId)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
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
     * 注意：流式接口方法瞬间返回 SseEmitter，真正的模型调用在后台线程。
     * 所以不能用 @ModelInvocationLog（AOP 拿不到真实数据），
     * 改为在 onCompleteResponse / onError 回调里手动记录日志。
     *
     * @RateLimit 仍然生效：在返回 SseEmitter 之前，AOP 会先检查限流。
     */
    @RateLimit(key = "practice:chat")
    @Override
    public SseEmitter chatStream(PracticeChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        List<ChatMessage> messages = buildMessages(request);

        executor.execute(() -> {
            try {
                long startTime = System.currentTimeMillis();

                streamingChatModel.chat(messages, new StreamingChatResponseHandler() {

                    @Override
                    public void onPartialResponse(String token) {
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
                            // 手动记录模型调用日志
                            saveStreamLog(requestId, costMs, response, "success", null);
                            emitter.send(SseEmitter.event().name("done").data(""));
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        long costMs = System.currentTimeMillis() - startTime;
                        log.error("[{}] 流式调用异常: {}", requestId, error.getMessage(), error);
                        // 手动记录失败日志
                        saveStreamLog(requestId, costMs, null, "fail", error.getMessage());
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
     * 流式接口手动记录模型调用日志（AOP 无法捕获异步回调，所以单独处理）
     */
    private void saveStreamLog(String requestId, long costMs, ChatResponse response,
                               String status, String errorMsg) {
        try {
            AiModelCallLog callLog = new AiModelCallLog();
            callLog.setBizType("stream_chat")
                    .setModelName(modelName)
                    .setRequestId(requestId)
                    .setDurationMs(costMs)
                    .setStatus(status)
                    .setErrorMsg(errorMsg)
                    .setCreateTime(new java.util.Date());

            // 从 ChatResponse 提取真实 token
            if (response != null && response.tokenUsage() != null) {
                callLog.setPromptTokens(response.tokenUsage().inputTokenCount());
                callLog.setCompletionTokens(response.tokenUsage().outputTokenCount());
                callLog.setTotalTokens(response.tokenUsage().totalTokenCount());
            }

            aiModelCallLogService.save(callLog);
        } catch (Exception e) {
            log.warn("[{}] 流式日志记录失败: {}", requestId, e.getMessage());
        }
    }

    /**
     * 结构化输出 - 通过 Prompt 约束模型返回 JSON
     *
     * 改动点（Day3 步骤4）：
     * - 之前：hardcode 一大段 system prompt 在代码里
     * - 现在：从 ai_prompt_template 表读取 "structured_analysis" 模板
     * - 好处：改 prompt 不用改代码、不用重启，直接在数据库改
     */
    @ModelInvocationLog(scene = "structured_output", async = false)
    @RateLimit(key = "practice:chat")
    @Override
    public PracticeChatResponse chatStructured(PracticeChatRequest request) {
        // 从数据库加载 structured_analysis 模板，渲染变量后作为 system prompt
        String systemPrompt = iAiPromptTemplateService.renderTemplate(
                iAiPromptTemplateService.getActiveByCode("structured_analysis").getId(),
                request.getTemplateVars()  // 结构化模板当前没有额外变量，传 null 也行
        );

        PracticeChatRequest structuredRequest = new PracticeChatRequest();
        structuredRequest.setMessage(request.getMessage());
        structuredRequest.setSystemPrompt(systemPrompt);

        return chat(structuredRequest);
    }

    /**
     * 构建消息列表（公共方法，三个接口都用）
     *
     * system prompt 来源优先级：
     * 1. promptCode → 从 DB 加载模板 + 渲染变量
     * 2. systemPrompt → 直接用传入文本
     * 3. 都没有 → 不加 system message
     */
    private List<ChatMessage> buildMessages(PracticeChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();

        // 决定 system prompt 来源
        String systemPrompt = resolveSystemPrompt(request);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }

        messages.add(new UserMessage(request.getMessage()));
        return messages;
    }

    /**
     * 解析 system prompt：
     * - 如果传了 promptCode，从数据库取模板并渲染
     * - 否则用传入的 systemPrompt 原文
     */
    private String resolveSystemPrompt(PracticeChatRequest request) {
        // 优先使用 promptCode 从数据库加载
        if (request.getPromptCode() != null && !request.getPromptCode().isBlank()) {
            try {
                var template = iAiPromptTemplateService.getActiveByCode(request.getPromptCode());
                if (template != null) {
                    String rendered = iAiPromptTemplateService.renderTemplate(
                            template.getId(), request.getTemplateVars());
                    log.info("使用模板 [{}] v{} | 渲染后长度={}",
                            template.getPromptCode(), template.getVersion(), rendered.length());
                    return rendered;
                }
            } catch (Exception e) {
                log.warn("加载模板失败 [{}]，降级使用 systemPrompt: {}",
                        request.getPromptCode(), e.getMessage());
            }
        }

        // 降级：使用直接传入的 systemPrompt
        return request.getSystemPrompt();
    }
}
