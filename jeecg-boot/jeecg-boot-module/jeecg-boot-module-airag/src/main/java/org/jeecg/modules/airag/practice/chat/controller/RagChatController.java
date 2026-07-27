package org.jeecg.modules.airag.practice.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.chat.entity.AiChatMessage;
import org.jeecg.modules.airag.practice.chat.entity.AiChatSession;
import org.jeecg.modules.airag.practice.chat.entity.AiToolChatCase;
import org.jeecg.modules.airag.practice.chat.service.ConversationMemoryService;
import org.jeecg.modules.airag.practice.chat.service.RagChatService;
import org.jeecg.modules.airag.practice.chat.vo.RagChatRequest;
import org.jeecg.modules.airag.practice.chat.vo.RagChatResponse;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;
import org.jeecg.modules.airag.practice.security.PracticeSecurityContext;
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
    @Resource
    private ConversationMemoryService conversationMemoryService;
    @Resource
    private PracticeSecurityContext securityContext;

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
        LoginUser user = securityContext.requireUser();
        return Result.OK(ragChatService.ragChat(request, user));
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
        return ragChatService.ragChatStream(request, securityContext.requireUser());
    }

    /**
     * 查询当前用户的会话列表
     * GET /practice/rag/sessions
     */
    @GetMapping("/sessions")
    @Operation(summary = "查询会话列表")
    public Result<List<AiChatSession>> listSessions() {
        String userId = securityContext.requireUser().getId();
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
        String userId = securityContext.requireUser().getId();
        List<AiChatMessage> messages = ragChatService.listMessages(sessionId,userId);
        return Result.OK(messages);
    }

    /**
     * 查询当前用户可访问的知识库列表（基于角色权限过滤）
     * GET /practice/rag/knowledge-bases
     */
    @GetMapping("/knowledge-bases")
    @Operation(summary = "查询可访问的知识库")
    public Result<List<AiKnowledgeBase>> listAccessibleKnowledgeBases() {
        List<AiKnowledgeBase> kbs = ragChatService.listAccessibleKnowledgeBases(securityContext.requireUser());
        return Result.OK(kbs);
    }


    /**
     * 生成会话结束总结（用户手动触发）
     * POST /practice/rag/sessions/{sessionId}/summary
     *
     * 对全部对话内容做结构化总结：主题 / 关键信息 / 完成操作 / 待跟进
     * 总结会覆盖到 session.summary 字段
     */
    @PostMapping("/sessions/{sessionId}/summary")
    @Operation(summary = "生成会话结束总结")
    public Result<String> generateSessionSummary(@PathVariable String sessionId) {
        LoginUser user = securityContext.requireUser();
        return Result.OK(conversationMemoryService.generateSessionEndSummary(sessionId, user.getId()));
    }

    /**
     * 将会话保存为测试用例
     * POST /practice/rag/sessions/{sessionId}/save-case
     *
     * 请求体：
     * {
     *   "caseName": "查询订单正常流程",
     *   "scenario": "order_query",
     *   "description": "用户询问订单B100状态，系统正确调用queryOrder返回结果",
     *   "expectedTools": "queryOrder"
     * }
     *
     * 自动从消息中提取实际调用的工具，与 expectedTools 比对判断是否通过
     */
    @PostMapping("/sessions/{sessionId}/save-case")
    @Operation(summary = "保存会话为测试用例")
    public Result<AiToolChatCase> saveAsCase(@PathVariable String sessionId,
                                             @RequestBody SaveCaseRequest  caseRequest) {
        if (caseRequest.getCaseName() == null || caseRequest.getCaseName().isBlank()) {
            return Result.error("用例名称不能为空");
        }
        String userId = securityContext.requireUser().getId();
        AiToolChatCase chatCase = conversationMemoryService.saveAsCase(
                sessionId,
                caseRequest.getCaseName(),
                caseRequest.getScenario(),
                caseRequest.getDescription(),
                caseRequest.getExpectedTools(),
                userId);
        return Result.OK(chatCase);
    }

    /**
     * 查询测试用例列表
     * GET /practice/rag/cases?scenario=order_query
     */
    @GetMapping("/cases")
    @Operation(summary = "查询测试用例列表")
    public Result<List<AiToolChatCase>> listCases(
            @RequestParam(required = false) String scenario) {
        LoginUser user = securityContext.requireUser();
        List<AiToolChatCase> cases = conversationMemoryService.listCases(
                scenario, user.getId(), securityContext.isAdmin(user));
        return Result.OK(cases);
    }

    @Data
    public static class SaveCaseRequest {
        private String caseName;
        private String scenario;
        private String description;
        private String expectedTools;
    }
}
