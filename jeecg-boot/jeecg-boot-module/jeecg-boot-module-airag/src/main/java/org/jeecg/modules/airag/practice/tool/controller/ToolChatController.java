package org.jeecg.modules.airag.practice.tool.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.practice.security.PracticeSecurityContext;
import org.jeecg.modules.airag.practice.tool.service.PendingToolCallService;
import org.jeecg.modules.airag.practice.tool.service.ToolChatService;
import org.jeecg.modules.airag.practice.tool.vo.ToolChatResponse;
import org.springframework.web.bind.annotation.*;

/**
 * Tool Calling 对话接口
 */
@Slf4j
@RestController
@RequestMapping("/practice/tool")
public class ToolChatController {

    @Resource
    private ToolChatService toolChatService;

    /** 写工具服务端确认服务。 */
    @Resource
    private PendingToolCallService pendingToolCallService;

    /** 当前登录用户解析服务。 */
    @Resource
    private PracticeSecurityContext securityContext;

    /**
     * Tool Calling 同步对话
     */
    @PostMapping("/chat")
    public Result<ToolChatResponse> chatWithTools(@RequestBody ToolChatRequest request) {
        if (StringUtils.isBlank(request.getMessage())) {
            return Result.error("message 不能为空");
        }
        log.info("[ToolChat] 收到请求: {}", request.getMessage());
        ToolChatResponse response = toolChatService.chatWithTools(request);
        log.info("[ToolChat] 完成 | 轮数={} | 工具调用次数={} | 耗时={}ms",
                response.getRounds(),
                response.getToolCalls() != null ? response.getToolCalls().size() : 0,
                response.getCostMs());
        return Result.OK(response);
    }

    /**
     * Tool Calling 流式对话（SSE）
     *
     * 直接写 HttpServletResponse 输出流，避免 SseEmitter 的 async dispatch 与 Shiro 冲突。
     */
    @PostMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public void chatStream(@RequestBody ToolChatRequest request, HttpServletResponse response) {
        log.info("[ToolChatStream] 收到流式请求: {}", request.getMessage());
        try {
            toolChatService.chatStream(request, response);
        } catch (Exception e) {
            log.error("[ToolChatStream] 处理异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 按服务端确认单中保存的精确参数执行写工具。
     */
    @PostMapping("/confirm-execute/{pendingCallId}")
    public Result<String> confirmExecute(@PathVariable String pendingCallId) {
        return Result.OK(pendingToolCallService.confirm(
                pendingCallId, securityContext.requireUser()));
    }

    /**
     * 取消当前用户的待确认写工具请求。
     */
    @PostMapping("/cancel/{pendingCallId}")
    public Result<String> cancel(@PathVariable String pendingCallId) {
        pendingToolCallService.cancel(pendingCallId, securityContext.requireUser());
        return Result.OK("已取消");
    }

    @lombok.Data
    public static class ToolChatRequest {
        private String message;
        private String sessionId;
    }
}
