package org.jeecg.modules.airag.practice.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 练习用 - 聊天响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeChatResponse {

    /**
     * 模型回答内容
     */
    private String content;

    /**
     * 使用的模型名称
     */
    private String model;

    /**
     * 本次调用耗时（毫秒）
     */
    private long costMs;

    /**
     * 请求唯一标识（用于日志追踪）
     */
    private String requestId;

    /**
     * 输入 token 数（模型 API 返回的真实值，非估算）
     * 来源：ChatResponse.tokenUsage().inputTokenCount()
     */
    private Integer promptTokens;

    /**
     * 输出 token 数（模型 API 返回的真实值，非估算）
     * 来源：ChatResponse.tokenUsage().outputTokenCount()
     */
    private Integer completionTokens;
}
