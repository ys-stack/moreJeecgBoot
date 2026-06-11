package org.jeecg.modules.airag.practice.vo;

import lombok.Data;

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
     * 系统提示词（可选，不传则使用默认）
     */
    private String systemPrompt;
}
