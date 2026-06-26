package org.jeecg.modules.airag.practice.tool.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.tool.cons.ToolCons;
import org.jeecg.modules.airag.practice.tool.controller.ToolChatController;
import org.jeecg.modules.airag.practice.tool.handler.ToolHandler;
import org.jeecg.modules.airag.practice.tool.entity.AiToolDefinition;
import org.jeecg.modules.airag.practice.tool.vo.ToolChatResponse;
import org.jeecg.modules.airag.practice.tool.vo.ToolChatResponse.ToolCallDetail;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Tool Calling 对话服务
 *
 * 编排整个 Tool Calling 流程：
 * 1. 通过 ToolCallingService 加载当前用户可用的工具
 * 2. 把用户消息 + 工具定义一起发给模型
 * 3. 模型返回工具调用请求 → 执行工具 → 把结果喂回模型 → 重复
 * 4. 直到模型给出最终文本回答，或达到最大轮数限制
 *
 * 手动控制循环的好处：可以记录每步中间状态、限制推理轮数、在前端展示"AI 的思考过程"
 */
@Slf4j
@Service
public class ToolChatService {

    private final OpenAiChatModel chatModel;

    @Resource
    private ToolCallingService toolCallingService;

    @Value("${practice.ai.model-name:mimo-v2.5-pro}")
    private String modelName;

    /** 最大推理轮数，防止死循环 */
    private static final int MAX_ROUNDS = 5;

    public ToolChatService(@Qualifier("practiceChatModel") OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Tool Calling 对话入口
     */
    public ToolChatResponse chatWithTools(ToolChatController.ToolChatRequest request) {
        long startTime = System.currentTimeMillis();
        String userMessage = request.getMessage();
        String sessionId = request.getSessionId();
        String messageId = UUID.randomUUID().toString();

        // confirmTools 防空
        if (request.getConfirmTools() == null) {
            request.setConfirmTools(Collections.emptyList());
        }

        // ============ 第 1 步：加载当前用户可用的工具 ============
        ToolCallingService.ToolBundle bundle = toolCallingService.buildToolMap();
        if (bundle.isEmpty()) {
            log.warn("没有可用工具，降级为普通对话");
            return fallbackChat(userMessage, startTime);
        }

        // ============ 第 2 步：构建消息列表 ============
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(userMessage));

        List<ToolCallDetail> allToolCallDetails = new ArrayList<>();
        int rounds = 0;

        // ============ 第 3 步：推理循环 ============
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            rounds = round;
            log.info("[Tool Calling] 第 {} 轮推理，消息数: {}", round, messages.size());

            // 构建 ChatRequest：消息 + 工具说明书
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(bundle.getSpecifications())
                    .build();

            // 调用模型
            ChatResponse response = chatModel.chat(chatRequest);
            AiMessage aiMessage = response.aiMessage();

            // ---------- 情况 A：模型直接给出文本回答 ----------
            if (!aiMessage.hasToolExecutionRequests()) {
                log.info("[Tool Calling] 第 {} 轮：模型给出最终回答", round);
                return ToolChatResponse.builder()
                        .content(aiMessage.text())
                        .model(modelName)
                        .costMs(System.currentTimeMillis() - startTime)
                        .rounds(rounds)
                        .toolCalls(allToolCallDetails)
                        .build();
            }

            // ---------- 情况 B：模型要调用工具 ----------
            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            log.info("[Tool Calling] 第 {} 轮：模型请求调用 {} 个工具", round, toolRequests.size());

            // 把模型的回复（包含工具调用请求）加入消息历史
            messages.add(aiMessage);

            // 逐个执行工具
            boolean needConfirm = false;
            for (ToolExecutionRequest toolRequest : toolRequests) {
                String toolName = toolRequest.name();
                String argsJson = toolRequest.arguments();
                log.info("[Tool Calling] 执行工具: {} | 参数: {}", toolName, argsJson);

                ToolHandler handler = bundle.getHandlerMap().get(toolName);
                AiToolDefinition def = bundle.getDefMap().get(toolName);
                String result;
                ToolCallDetail detail;
                long toolStart = System.currentTimeMillis();

                if (handler != null && def != null) {
                    // 写操作二次确认：需要确认 且 用户未确认 → 中断，返回前端
                    if (def.getRequireConfirm() == 1 && !request.getConfirmTools().contains(toolName)) {
                        log.info("[Tool Calling] 工具 {} 需要用户确认，暂停执行", toolName);
                        detail = ToolCallDetail.builder()
                                .toolCode(toolName)
                                .toolName(def.getToolName())
                                .inputParams(argsJson)
                                .status(ToolCons.status_pending_confirm)
                                .build();
                        allToolCallDetails.add(detail);
                        needConfirm = true;
                        continue;
                    }
                    // 正常执行：调 Handler + 记录日志
                    result = toolCallingService.executeTool(toolName, handler, def, argsJson, sessionId, messageId);
                    long toolDuration = System.currentTimeMillis() - toolStart;

                    detail = ToolCallDetail.builder()
                            .toolCode(toolName)
                            .toolName(def.getToolName())
                            .inputParams(argsJson)
                            .outputResult(result)
                            .status(result.contains("\"error\"") ? "error" : "success")
                            .durationMs(toolDuration)
                            .build();
                } else {
                    // 工具不存在
                    result = "{\"error\": \"工具 " + toolName + " 未找到或已停用\"}";
                    long toolDuration = System.currentTimeMillis() - toolStart;

                    detail = ToolCallDetail.builder()
                            .toolCode(toolName)
                            .toolName(toolName)
                            .inputParams(argsJson)
                            .outputResult(result)
                            .status("error")
                            .durationMs(toolDuration)
                            .build();
                    log.warn("[Tool Calling] 工具未找到: {}", toolName);
                }

                allToolCallDetails.add(detail);
                // 把工具执行结果加入消息历史
                messages.add(ToolExecutionResultMessage.from(toolRequest, result));
            }

            // 有待确认的工具 → 立即返回，不继续下一轮推理
            if (needConfirm) {
                return ToolChatResponse.builder()
                        .content("以下操作需要您确认后才会执行")
                        .model(modelName)
                        .costMs(System.currentTimeMillis() - startTime)
                        .rounds(rounds)
                        .toolCalls(allToolCallDetails)
                        .needsConfirm(true)
                        .build();
            }
        }

        // 达到最大轮数，强制结束
        log.warn("[Tool Calling] 达到最大轮数 {}，停止执行", MAX_ROUNDS);
        return ToolChatResponse.builder()
                .content("抱歉，我尝试了 " + MAX_ROUNDS + " 轮推理但还没得出最终答案，请尝试简化问题。")
                .model(modelName)
                .costMs(System.currentTimeMillis() - startTime)
                .rounds(rounds)
                .toolCalls(allToolCallDetails)
                .build();
    }

    /**
     * 降级处理：没有可用工具时，走普通对话模式
     */
    private ToolChatResponse fallbackChat(String userMessage, long startTime) {
        try {
            ChatResponse response = chatModel.chat(new UserMessage(userMessage));
            return ToolChatResponse.builder()
                    .content(response.aiMessage().text())
                    .model(modelName)
                    .costMs(System.currentTimeMillis() - startTime)
                    .rounds(1)
                    .toolCalls(Collections.emptyList())
                    .build();
        } catch (Exception e) {
            return ToolChatResponse.builder()
                    .content("模型调用失败: " + e.getMessage())
                    .model(modelName)
                    .costMs(System.currentTimeMillis() - startTime)
                    .rounds(0)
                    .toolCalls(Collections.emptyList())
                    .build();
        }
    }
}
