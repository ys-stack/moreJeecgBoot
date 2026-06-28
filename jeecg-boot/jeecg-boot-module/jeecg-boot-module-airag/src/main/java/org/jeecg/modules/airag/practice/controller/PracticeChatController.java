package org.jeecg.modules.airag.practice.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.practice.service.IPracticeChatService;
import org.jeecg.modules.airag.practice.vo.PracticeChatRequest;
import org.jeecg.modules.airag.practice.vo.PracticeChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 练习 - 聊天接口
 * 接口列表：
 *   POST /practice/chat/send        - 普通聊天（同步返回完整回答）
 *   POST /practice/chat/stream      - 流式聊天（SSE 逐字返回）
 *   POST /practice/chat/structured  - 结构化输出（返回 JSON 格式分析结果）
 */
@Slf4j
@RestController
@RequestMapping("/practice/chat")
public class PracticeChatController {

    @Resource
    IPracticeChatService practiceChatService;

    /**
     * 普通聊天 - 同步返回
     */
    @PostMapping(value = "/send")
    public Result<PracticeChatResponse> chat(@RequestBody PracticeChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Result.error("message 不能为空");
        }
        PracticeChatResponse response = practiceChatService.chat(request);
        return Result.OK(response);
    }

    /**
     * 流式聊天 - SSE 逐字返回
     *
     * 输入：{"message": "你的问题"}
     * 输出：SSE 事件流，每个 event 的 data 是一段文本片段
     *
     * 用浏览器或 Postman 直接访问，能看到逐字输出的效果。
     * 前端对接时用 EventSource 或 fetch + ReadableStream。
     */
    @PostMapping(value = "/stream")
    public SseEmitter chatStream(@RequestBody PracticeChatRequest request) {
        return practiceChatService.chatStream(request);
    }

    /**
     * 结构化输出 - 需求分析助手
     *
     * 输入：{"message": "做一个用户注册功能，支持邮箱和手机号注册"}
     * 输出：结构化 JSON，包含背景、目标、接口设计、数据表设计、风险点
     *
     * 这个接口展示了 Prompt 工程的实际应用：
     * 通过 System Prompt 约束模型输出格式，让程序可以直接解析和使用。
     */
    @PostMapping(value = "/structured")
    public Result<PracticeChatResponse> chatStructured(@RequestBody PracticeChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Result.error("message 不能为空");
        }
        PracticeChatResponse response = practiceChatService.chatStructured(request);
        return Result.OK(response);
    }
}
