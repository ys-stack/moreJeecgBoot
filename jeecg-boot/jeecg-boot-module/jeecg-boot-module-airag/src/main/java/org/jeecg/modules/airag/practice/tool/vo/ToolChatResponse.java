package org.jeecg.modules.airag.practice.tool.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Tool Calling 聊天响应
 *
 * 除了最终的 content 回答，还附带每次工具调用的详细日志，
 * 方便前端展示"AI 的思考过程"（调了哪些工具、传了什么参数、返回了什么结果）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolChatResponse {

    /** 模型的最终自然语言回答 */
    private String content;

    /** 使用的模型名称 */
    private String model;

    /** 总耗时（毫秒） */
    private long costMs;

    /** 推理轮数（1=模型直接回答，2+=经过工具调用） */
    private int rounds;

    /** 每次工具调用的详细日志 */
    private List<ToolCallDetail> toolCalls;

    /** 是否有工具等待用户确认（前端据此展示确认 UI） */
    private boolean needsConfirm;

    private String sessionId;       // 返回给前端，下次对话带上
    private String sessionTitle;    // 会话标题（首次对话时返回）

    /**
     * 单次工具调用的详细信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallDetail {
        /** 工具编码（如 queryOrder） */
        private String toolCode;
        /** 工具名称（如 查询订单） */
        private String toolName;
        /** 模型传入的调用参数 JSON */
        private String inputParams;
        /** 工具执行返回的结果 JSON */
        private String outputResult;
        /** 执行状态: success / error */
        private String status;
        /** 执行耗时（毫秒） */
        private long durationMs;
    }
}
