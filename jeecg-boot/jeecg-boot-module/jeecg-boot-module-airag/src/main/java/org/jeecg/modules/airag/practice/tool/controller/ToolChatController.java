package org.jeecg.modules.airag.practice.tool.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.practice.tool.service.ToolChatService;
import org.jeecg.modules.airag.practice.tool.vo.ToolChatResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Tool Calling 对话接口
 */
@Slf4j
@RestController
@RequestMapping("/practice/tool")
public class ToolChatController {

    @Resource
    private ToolChatService toolChatService;

    /**
     * Tool Calling 同步对话
     */
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
            ToolChatResponse response = toolChatService.chatWithTools(request);
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

    @lombok.Data
    public static class ToolChatRequest {
        private String message;
        private String sessionId;
        private List<String> confirmTools;
    }
}
