package org.jeecg.modules.airag.practice.tool.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.tool.ToolHandler;
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
 * 这是 Tool Calling 的"编排者"，负责把整个流程串起来：
 *
 * 1. 通过 Dispatcher 从数据库加载所有 active 工具
 * 2. 把用户消息 + 工具定义（ToolSpecification）一起发给模型
 * 3. 模型可能返回两种情况：
 *    a) 纯文本回答 → 直接返回（没有调用工具）
 *    b) 工具调用请求 → 执行工具 → 把结果喂回模型 → 重复
 * 4. 直到模型给出最终文本回答，或达到最大轮数限制
 *
 * 手动控制循环的好处（对比 LangChain4j 自动模式）：
 * - 可以记录每一步的中间状态（调了什么工具、传了什么参数、返回了什么）
 * - 可以限制最大推理轮数，防止死循环
 * - 可以在前端展示"AI 的思考过程"
 *
 * 整个流程类比 Java：
 *   Dispatcher = ServiceRegistry（服务注册中心）
 *   ToolSpecification = Swagger 文档（告诉模型有哪些 API）
 *   ToolHandler = Controller 方法（实际执行业务逻辑）
 *   这个类 = Gateway / 网关（编排请求路由和结果聚合）
 */
@Slf4j
@Service
public class ToolChatService {

    private final OpenAiChatModel chatModel;

    @Resource
    private ToolCallingDispatcher dispatcher;

    @Value("${practice.ai.model-name:mimo-v2.5-pro}")
    private String modelName;

    /**
     * 最大推理轮数。
     * 模型可能连续调多个工具，这个限制防止死循环。
     * 比如"查订单 12345，如果超时了就创建工单"需要 2 轮（查 + 写），5 轮绰绰有余。
     */
    private static final int MAX_ROUNDS = 5;

    public ToolChatService(@Qualifier("practiceChatModel") OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Tool Calling 对话入口
     *
     * @param userMessage 用户的问题，如 "帮我查一下订单 B100"
     * @return 包含最终回答 + 工具调用详情的响应
     */
    public ToolChatResponse chatWithTools(String userMessage) {
        long startTime = System.currentTimeMillis();

        // ============ 第 1 步：加载所有 active 工具 ============
        ToolCallingDispatcher.LoadedTools loadedTools = dispatcher.loadActiveTools();
        if (loadedTools.isEmpty()) {
            log.warn("没有 active 工具，降级为普通对话");
            return fallbackChat(userMessage, startTime);
        }

        // ============ 第 2 步：构建消息列表 ============
        // 消息列表是"对话历史"，每轮都会追加新消息
        // 初始只有用户的问题
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(userMessage));

        // 用于收集每轮工具调用的详情（最终返回给前端展示）
        List<ToolCallDetail> allToolCallDetails = new ArrayList<>();
        int rounds = 0;

        // ============ 第 3 步：推理循环 ============
        // 模型可能调 0 个工具（直接回答）、1 个工具、或多个工具
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            rounds = round;
            log.info("[Tool Calling] 第 {} 轮推理，消息数: {}", round, messages.size());

            // 构建 ChatRequest：消息 + 工具定义
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(loadedTools.getSpecifications())
                    .build();

            // 调用模型
            ChatResponse response = chatModel.chat(request);
            AiMessage aiMessage = response.aiMessage();

            // ---------- 情况 A：模型直接给出文本回答，不需要调工具 ----------
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
            log.info("[Tool Calling] 第 {} 轮：模型请求调用 {} 个工具",
                    round, aiMessage.toolExecutionRequests().size());

            // 把模型的回复（包含工具调用请求）加入消息历史
            messages.add(aiMessage);

            // 逐个执行工具
            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                String toolName = toolRequest.name();
                log.info("[Tool Calling] 执行工具: {} | 参数: {}", toolName, toolRequest.arguments());

                // 从 Dispatcher 加载的映射中查找 Handler
                ToolHandler handler = loadedTools.getHandlers().get(toolName);
                AiToolDefinition def = loadedTools.getDefinitions().get(toolName);

                String result;
                ToolCallDetail detail;
                long toolStart = System.currentTimeMillis();

                if (handler != null && def != null) {
                    // 正常执行：调 Handler + 记录日志
                    result = dispatcher.executeTool(toolRequest, def, handler, null);
                    long toolDuration = System.currentTimeMillis() - toolStart;

                    detail = ToolCallDetail.builder()
                            .toolCode(toolName)
                            .toolName(def.getToolName())
                            .inputParams(toolRequest.arguments())
                            .outputResult(result)
                            .status(result.contains("\"error\"") ? "error" : "success")
                            .durationMs(toolDuration)
                            .build();
                } else {
                    // 工具不存在：返回错误信息给模型
                    result = "{\"error\": \"工具 " + toolName + " 未找到或已停用\"}";
                    long toolDuration = System.currentTimeMillis() - toolStart;

                    detail = ToolCallDetail.builder()
                            .toolCode(toolName)
                            .toolName(toolName)
                            .inputParams(toolRequest.arguments())
                            .outputResult(result)
                            .status("error")
                            .durationMs(toolDuration)
                            .build();
                    log.warn("[Tool Calling] 工具未找到: {}", toolName);
                }

                allToolCallDetails.add(detail);

                // 把工具执行结果加入消息历史
                // ToolExecutionResultMessage 会把结果关联到对应的工具调用请求
                messages.add(ToolExecutionResultMessage.from(toolRequest, result));
            }

            // 继续下一轮循环：带着工具结果再次调用模型
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
     * 降级处理：没有 active 工具时，走普通对话模式
     * 这样即使工具全被禁用，接口也不会报错
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
