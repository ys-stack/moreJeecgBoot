package org.jeecg.modules.airag.practice.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.chat.entity.AiChatMessage;
import org.jeecg.modules.airag.practice.chat.entity.AiChatSession;
import org.jeecg.modules.airag.practice.chat.service.RagChatService;
import org.jeecg.modules.airag.practice.chat.vo.RagChatRequest;
import org.jeecg.modules.airag.practice.chat.vo.RagChatResponse;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * RAG 聊天接口（需登录）
 *
 * 完整链路：用户提问 → 向量检索知识库 → 构建带上下文的 Prompt → 调用大模型 → 返回答案 + 参考来源
 *
 * 接口列表：
 *   POST /practice/rag/chat          - RAG 聊天（核心接口）
 *   GET  /practice/rag/sessions      - 查询当前用户的会话列表
 *   GET  /practice/rag/messages/{sessionId} - 查询会话消息历史
 *
 * 权限说明：
 *   不使用 @IgnoreAuth，走 Shiro JWT 鉴权。
 *   通过 SecurityUtils.getSubject().getPrincipal() 获取当前登录用户。
 *   前端请求需携带 X-Access-Token 头（登录后自动携带）。
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-20
 */
@Slf4j
@RestController
@RequestMapping("/practice/rag")
@Tag(name = "RAG聊天接口")
public class RagChatController {

    @Resource
    private RagChatService ragChatService;

    /**
     * RAG 聊天 - 核心接口
     *
     * 请求示例：
     * POST /practice/rag/chat
     * {
     *   "query": "Java并发有哪些方式？",
     *   "knowledgeBaseId": "知识库ID（可选）",
     *   "topK": 5
     * }
     *
     * 首次对话不传 sessionId，后端自动创建并返回。
     * 后续对话带上 sessionId 实现多轮会话。
     */
    @PostMapping("/chat")
    @Operation(summary = "RAG聊天")
    public Result<RagChatResponse> ragChat(@RequestBody RagChatRequest request) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return Result.error("query 不能为空");
        }
        try {
            String userId = getLoginUserId();
            RagChatResponse response = ragChatService.ragChat(request, userId);
            return Result.OK(response);
        } catch (Exception e) {
            log.error("RAG 聊天失败: {}", e.getMessage(), e);
            return Result.error("RAG 聊天失败: " + e.getMessage());
        }
    }

    /**
     * RAG 流式聊天 - SSE 接口
     *
     * 与 /chat 相同的请求参数，但返回 SSE 流：
     *   event: meta    → JSON {sessionId, references[]}（首条事件）
     *   event: message → 逐 token 文本
     *   event: done    → 流结束
     *   event: error   → 错误信息
     *
     * 前端用 fetch + ReadableStream 消费。
     */
    @PostMapping("/chat/stream")
    @Operation(summary = "RAG流式聊天(SSE)")
    public SseEmitter ragChatStream(@RequestBody RagChatRequest request) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error").data("query 不能为空"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        String userId = getLoginUserId();
        return ragChatService.ragChatStream(request, userId);
    }

    /**
     * 查询当前用户的会话列表
     * GET /practice/rag/sessions
     */
    @GetMapping("/sessions")
    @Operation(summary = "查询会话列表")
    public Result<List<AiChatSession>> listSessions() {
        String userId = getLoginUserId();
        List<AiChatSession> sessions = ragChatService.listSessions(userId);
        return Result.OK(sessions);
    }

    /**
     * 查询会话消息历史
     * GET /practice/rag/messages/{sessionId}
     */
    @GetMapping("/messages/{sessionId}")
    @Operation(summary = "查询会话消息历史")
    public Result<List<AiChatMessage>> listMessages(@PathVariable String sessionId) {
        List<AiChatMessage> messages = ragChatService.listMessages(sessionId);
        return Result.OK(messages);
    }

    /**
     * 查询当前用户可访问的知识库列表（基于角色权限过滤）
     * GET /practice/rag/knowledge-bases
     */
    @GetMapping("/knowledge-bases")
    @Operation(summary = "查询可访问的知识库")
    public Result<List<AiKnowledgeBase>> listAccessibleKnowledgeBases() {
        List<AiKnowledgeBase> kbs = ragChatService.listAccessibleKnowledgeBases();
        return Result.OK(kbs);
    }

    /**
     * 从 Shiro 获取当前登录用户ID
     * 因为不使用 @IgnoreAuth，JwtFilter 已完成鉴权，此处一定能拿到用户
     */
    private String getLoginUserId() {
        LoginUser sysUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        return sysUser.getId();
    }
}
