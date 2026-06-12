package org.jeecg.modules.airag.practice.vo;

import lombok.Data;

import java.util.Map;

/**
 * 练习用 - 聊天请求参数
 */
@Data
public class PracticeChatRequest {

    /**
     * 用户输入的问题
     */
    private String message;

    /**
     * 系统提示词（可选，直接传文本）
     */
    private String systemPrompt;

    /**
     * Prompt 模板编码（可选，传了就从数据库加载模板）
     * 和 systemPrompt 二选一：promptCode 优先
     */
    private String promptCode;

    /**
     * 模板变量（可选，配合 promptCode 使用）
     * 例如 {"orderInfo": "订单号:12345, 状态:已发货"}
     */
    private Map<String, String> templateVars;
}
