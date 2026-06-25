package org.jeecg.modules.airag.practice.tool.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.config.shiro.IgnoreAuth;
import org.jeecg.modules.airag.practice.tool.service.ToolChatService;
import org.jeecg.modules.airag.practice.tool.vo.ToolChatResponse;
import org.springframework.web.bind.annotation.*;

/**
 * Tool Calling 对话接口
 *
 * 接口列表：
 *   POST /practice/tool/chat - 带工具调用的对话
 *
 * 请求体示例：
 * {
 *   "message": "帮我查一下订单 B100 的状态"
 * }
 *
 * 模型会根据问题自动选择工具：
 *   "查订单 B100" → 自动调 queryOrder
 *   "张三的电话是多少" → 自动调 queryUser
 *   "帮我提个 bug 工单" → 自动调 createTicket
 *   "今天天气怎么样" → 不调工具，直接回答
 */
@Slf4j
@RestController
@RequestMapping("/practice/tool")
public class ToolChatController {

    @Resource
    private ToolChatService toolChatService;

    /**
     * Tool Calling 对话
     *
     * 和普通对话的区别：模型可以自主决定是否调用工具、调用哪个工具、传什么参数。
     * 响应中的 toolCalls 字段记录了每次工具调用的详细信息（参数、结果、耗时），
     * 前端可以用来展示"AI 的思考过程"。
     */
    @IgnoreAuth
    @PostMapping("/chat")
    public Result<ToolChatResponse> chatWithTools(@RequestBody ToolChatRequest request) {
        if (StringUtils.isBlank(request.getMessage())) {
            return Result.error("message 不能为空");
        }

        if (StringUtils.isBlank(request.getSessionId())) {
            return Result.error("sessionId 不能为空");
        }

        log.info("[ToolChat] 收到请求: {}", request.getMessage());

        try {
            ToolChatResponse response = toolChatService.chatWithTools(ToolChatRequest request);
            log.info("[ToolChat] 完成 | 轮数={} | 工具调用次数={} | 耗时={}ms",
                    response.getRounds(),
                    response.getToolCalls() != null ? response.getToolCalls().size() : 0,
                    response.getCostMs());
            return Result.OK(response);
        } catch (Exception e) {
            log.error("[ToolChat] 处理失败: {}", e.getMessage(), e);
            return Result.error("Tool Calling 对话失败: " + e.getMessage());
        }
    }

    /**
     * Tool Calling 请求体
     * 目前只需要 message 字段，后续可以扩展（如指定会话 ID、排除某些工具等）
     */
    @lombok.Data
    public static class ToolChatRequest {
        /** 用户输入的问题 */
        private String message;
        /** 用户输入的问题 */
        private String SessionId;

    }
}
