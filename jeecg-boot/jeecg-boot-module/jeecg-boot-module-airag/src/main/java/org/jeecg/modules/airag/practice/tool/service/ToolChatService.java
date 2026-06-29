package org.jeecg.modules.airag.practice.tool.service;

import com.alibaba.fastjson.JSON;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.shiro.SecurityUtils;
import org.apache.tika.utils.StringUtils;
import org.jeecg.common.system.vo.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.chat.entity.AiChatMessage;
import org.jeecg.modules.airag.practice.chat.entity.AiChatSession;
import org.jeecg.modules.airag.practice.chat.mapper.AiChatMessageMapper;
import org.jeecg.modules.airag.practice.chat.mapper.AiChatSessionMapper;
import org.jeecg.modules.airag.practice.tool.cons.ToolCons;
import org.jeecg.modules.airag.practice.tool.controller.ToolChatController;
import org.jeecg.modules.airag.practice.tool.handler.ToolHandler;
import org.jeecg.modules.airag.practice.tool.entity.AiToolDefinition;
import org.jeecg.modules.airag.practice.tool.vo.ToolChatResponse;
import org.jeecg.modules.airag.practice.tool.vo.ToolChatResponse.ToolCallDetail;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;

/**
 * Tool Calling 对话服务
 *
 * 支持两种模式：
 * - 同步：chatWithTools() — 一次性返回完整结果
 * - 流式：chatStream() — 通过 SSE 实时推送每个阶段（直接写 HttpServletResponse，不走 SseEmitter）
 */
@Slf4j
@Service
public class ToolChatService {

    private final OpenAiChatModel chatModel;
    private final OpenAiStreamingChatModel streamingChatModel;

    @Resource
    private ToolCallingService toolCallingService;
    @Resource
    private AiChatMessageMapper aiChatMessageMapper;
    @Resource
    private AiChatSessionMapper aiChatSessionMapper;

    @Value("${practice.ai.model-name:mimo-v2.5-pro}")
    private String modelName;

    private static final int MAX_ROUNDS = 5;

    public ToolChatService(@Qualifier("practiceChatModel") OpenAiChatModel chatModel,
                           @Qualifier("practiceStreamingChatModel") OpenAiStreamingChatModel streamingChatModel) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
    }

    // ======================== SSE 流式接口 ========================

    /**
     * 流式对话 — 直接写 HttpServletResponse 输出流
     *
     * 不用 SseEmitter 的原因：SseEmitter 走 Tomcat async dispatch，
     * Spring 的 FrameworkServlet.publishRequestHandledEvent 会访问 Shiro，
     * 但 async 线程没有 SecurityManager，导致 UnavailableSecurityManagerException。
     *
     * 直接写 response 输出流，请求在同一线程内完成，不触发 async dispatch。
     */
    public void chatStream(ToolChatController.ToolChatRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = response.getWriter();

        // 获取当前用户（同一线程，Shiro 可用）
        LoginUser currentUser = null;
        try {
            currentUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        } catch (Exception ignored) {}

        long startTime = System.currentTimeMillis();
        String userMessage = request.getMessage();
        String sessionId = request.getSessionId();
        String messageId = UUID.randomUUID().toString();

        if (request.getConfirmTools() == null) {
            request.setConfirmTools(Collections.emptyList());
        }

        // 加载工具
        ToolCallingService.ToolBundle bundle = toolCallingService.buildToolMap(currentUser);
        if (bundle.isEmpty()) {
            sendSse(writer, "error", "没有可用工具");
            writer.flush();
            return;
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(userMessage));

        try {
            for (int round = 1; round <= MAX_ROUNDS; round++) {
                // 通知前端：开始第 N 轮思考
                sendSse(writer, "thinking", JSON.toJSONString(Map.of("round", round)));
                writer.flush();

                // 构建请求
                ChatRequest chatRequest = ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(bundle.getSpecifications())
                        .build();

                // 流式调用模型，收集完整响应
                StringBuilder tokenBuffer = new StringBuilder();
                AiMessage[] aiMessageHolder = new AiMessage[1];
                Throwable[] errorHolder = new Throwable[1];
                CountDownLatch latch = new CountDownLatch(1);

                streamingChatModel.chat(chatRequest, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        tokenBuffer.append(token);
                        sendSse(writer, "message", token);
                        writer.flush();
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse chatResponse) {
                        aiMessageHolder[0] = chatResponse.aiMessage();
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        errorHolder[0] = error;
                        latch.countDown();
                    }
                });

                // 等待流式响应完成（最多 60 秒）
                if (!latch.await(60, TimeUnit.SECONDS)) {
                    sendSse(writer, "error", "模型响应超时");
                    writer.flush();
                    return;
                }

                if (errorHolder[0] != null) {
                    sendSse(writer, "error", "模型调用异常: " + errorHolder[0].getMessage());
                    writer.flush();
                    return;
                }

                AiMessage aiMessage = aiMessageHolder[0];
                if (aiMessage == null) {
                    sendSse(writer, "error", "模型未返回响应");
                    writer.flush();
                    return;
                }

                // ---------- 情况 A：模型直接给出文本回答 ----------
                if (!aiMessage.hasToolExecutionRequests()) {
                    long costMs = System.currentTimeMillis() - startTime;
                    sendSse(writer, "done", JSON.toJSONString(Map.of(
                            "model", modelName, "costMs", costMs, "rounds", round)));
                    writer.flush();
                    return;
                }

                // ---------- 情况 B：模型要调用工具 ----------
                messages.add(aiMessage);

                boolean needConfirm = false;
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    String toolName = toolRequest.name();
                    String argsJson = toolRequest.arguments();

                    ToolHandler handler = bundle.getHandlerMap().get(toolName);
                    AiToolDefinition def = bundle.getDefMap().get(toolName);

                    if (handler != null && def != null) {
                        // 写操作二次确认
                        if (def.getRequireConfirm() == 1 && !request.getConfirmTools().contains(toolName)) {
                            sendSse(writer, "confirm", JSON.toJSONString(Map.of(
                                    "toolCode", toolName, "toolName", def.getToolName(), "inputParams", argsJson)));
                            writer.flush();
                            needConfirm = true;
                            continue;
                        }

                        // 正在执行工具
                        sendSse(writer, "tool_call", JSON.toJSONString(Map.of(
                                "toolCode", toolName, "toolName", def.getToolName(), "inputParams", argsJson)));
                        writer.flush();

                        // 执行
                        long toolStart = System.currentTimeMillis();
                        String result = toolCallingService.executeTool(
                                toolName, handler, def, argsJson, sessionId, messageId, currentUser);
                        long toolDuration = System.currentTimeMillis() - toolStart;

                        sendSse(writer, "tool_result", JSON.toJSONString(Map.of(
                                "toolCode", toolName, "toolName", def.getToolName(),
                                "outputResult", result,
                                "status", result.contains("\"error\"") ? "error" : "success",
                                "durationMs", toolDuration)));
                        writer.flush();

                        messages.add(ToolExecutionResultMessage.from(toolRequest, result));
                    } else {
                        String result = "{\"error\": \"工具 " + toolName + " 未找到或已停用\"}";

                        sendSse(writer, "tool_result", JSON.toJSONString(Map.of(
                                "toolCode", toolName, "toolName", toolName,
                                "outputResult", result, "status", "error", "durationMs", 0)));
                        writer.flush();

                        messages.add(ToolExecutionResultMessage.from(toolRequest, result));
                    }
                }

                if (needConfirm) {
                    long costMs = System.currentTimeMillis() - startTime;
                    sendSse(writer, "done", JSON.toJSONString(Map.of(
                            "model", modelName, "costMs", costMs, "rounds", round, "needsConfirm", true)));
                    writer.flush();
                    return;
                }
            }

            // 达到最大轮数
            sendSse(writer, "error", "达到最大推理轮数 " + MAX_ROUNDS + "，停止执行");
            writer.flush();

        } catch (Exception e) {
            log.error("[ToolChatStream] 处理异常: {}", e.getMessage(), e);
            sendSse(writer, "error", "处理异常: " + e.getMessage());
            writer.flush();
        }
    }

    /**
     * 发送一条 SSE 事件
     * 格式：event:xxx\ndata:yyy\n\n
     */
    private void sendSse(PrintWriter writer, String event, String data) {
        writer.print("event:" + event + "\n");
        writer.print("data:" + data + "\n\n");
    }

    // ======================== 同步接口（保留） ========================

    public ToolChatResponse chatWithTools(ToolChatController.ToolChatRequest request) {
        long startTime = System.currentTimeMillis();
        String userMessage = request.getMessage();
        String sessionId = request.getSessionId();
        String messageId = UUID.randomUUID().toString();

        if (request.getConfirmTools() == null) {
            request.setConfirmTools(Collections.emptyList());
        }
        //会话管理
        AiChatSession session;
        LoginUser sysUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        if (StringUtils.isBlank(sessionId)) {
            //创建新会话
            session = createSession(request, sysUser);
        }else {
            session = aiChatSessionMapper.selectById(sessionId);
        }

        //保存用户消息
        AiChatMessage userMsg = new AiChatMessage()
                .setSessionId(session.getId())
                .setRole("user")
                .setContent(request.getMessage())
                .setStatus("success")
                .setCreateBy(sysUser.getId())
                .setCreateTime(new Date());
        aiChatMessageMapper.insert(userMsg);

        ToolCallingService.ToolBundle bundle = toolCallingService.buildToolMap();
        if (bundle.isEmpty()) {
            return fallbackChat(userMessage, startTime);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(userMessage));

        List<ToolCallDetail> allToolCallDetails = new ArrayList<>();
        int rounds = 0;

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            rounds = round;

            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(bundle.getSpecifications())
                    .build();

            ChatResponse response = chatModel.chat(chatRequest);
            AiMessage aiMessage = response.aiMessage();

            if (!aiMessage.hasToolExecutionRequests()) {
                return ToolChatResponse.builder()
                        .content(aiMessage.text()).model(modelName)
                        .costMs(System.currentTimeMillis() - startTime).rounds(rounds)
                        .toolCalls(allToolCallDetails).build();
            }

            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            messages.add(aiMessage);

            boolean needConfirm = false;
            for (ToolExecutionRequest toolRequest : toolRequests) {
                String toolName = toolRequest.name();
                String argsJson = toolRequest.arguments();

                ToolHandler handler = bundle.getHandlerMap().get(toolName);
                AiToolDefinition def = bundle.getDefMap().get(toolName);
                String result;
                ToolCallDetail detail;
                long toolStart = System.currentTimeMillis();

                if (handler != null && def != null) {
                    if (def.getRequireConfirm() == 1 && !request.getConfirmTools().contains(toolName)) {
                        detail = ToolCallDetail.builder()
                                .toolCode(toolName).toolName(def.getToolName())
                                .inputParams(argsJson).status(ToolCons.status_pending_confirm).build();
                        allToolCallDetails.add(detail);
                        needConfirm = true;
                        continue;
                    }
                    result = toolCallingService.executeTool(toolName, handler, def, argsJson, sessionId, messageId);
                    long toolDuration = System.currentTimeMillis() - toolStart;
                    detail = ToolCallDetail.builder()
                            .toolCode(toolName).toolName(def.getToolName())
                            .inputParams(argsJson).outputResult(result)
                            .status(result.contains("\"error\"") ? "error" : "success")
                            .durationMs(toolDuration).build();
                } else {
                    result = "{\"error\": \"工具 " + toolName + " 未找到或已停用\"}";
                    long toolDuration = System.currentTimeMillis() - toolStart;
                    detail = ToolCallDetail.builder()
                            .toolCode(toolName).toolName(toolName)
                            .inputParams(argsJson).outputResult(result)
                            .status("error").durationMs(toolDuration).build();
                }

                allToolCallDetails.add(detail);
                messages.add(ToolExecutionResultMessage.from(toolRequest, result));
            }

            if (needConfirm) {
                return ToolChatResponse.builder()
                        .content("以下操作需要您确认后才会执行").model(modelName)
                        .costMs(System.currentTimeMillis() - startTime).rounds(rounds)
                        .toolCalls(allToolCallDetails).needsConfirm(true).build();
            }
        }

        return ToolChatResponse.builder()
                .content("抱歉，我尝试了 " + MAX_ROUNDS + " 轮推理但还没得出最终答案。")
                .model(modelName).costMs(System.currentTimeMillis() - startTime)
                .rounds(rounds).toolCalls(allToolCallDetails).build();
    }

    /*
     * @Author: ys
     * @Date: 2026/6/29 星期一 22:51
     * @Desc: 创建新会话
     */
    private AiChatSession createSession(ToolChatController.ToolChatRequest request, LoginUser sysUser) {
        AiChatSession session = new AiChatSession()
                .setTitle(request.getMessage().length() > 50
                        ? request.getMessage().substring(0, 50) + "..."
                        : request.getMessage())
                .setUserId(sysUser.getId())
                .setModelProvider("openai")
                .setModelName(modelName)
                .setStatus("active")
                .setMessageCount(0)
                .setCreateBy(sysUser.getId())
                .setCreateTime(new Date());
        aiChatSessionMapper.insert(session);
        log.info("Tool Chat 会话创建成功: id={}, title='{}'", session.getId(), session.getTitle());
        return session;
    }

    private ToolChatResponse fallbackChat(String userMessage, long startTime) {
        try {
            ChatResponse response = chatModel.chat(new UserMessage(userMessage));
            return ToolChatResponse.builder()
                    .content(response.aiMessage().text()).model(modelName)
                    .costMs(System.currentTimeMillis() - startTime).rounds(1)
                    .toolCalls(Collections.emptyList()).build();
        } catch (Exception e) {
            return ToolChatResponse.builder()
                    .content("模型调用失败: " + e.getMessage()).model(modelName)
                    .costMs(System.currentTimeMillis() - startTime).rounds(0)
                    .toolCalls(Collections.emptyList()).build();
        }
    }
}
