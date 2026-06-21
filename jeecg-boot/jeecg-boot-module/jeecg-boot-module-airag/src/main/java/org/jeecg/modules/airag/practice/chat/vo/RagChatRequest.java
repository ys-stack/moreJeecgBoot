package org.jeecg.modules.airag.practice.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * RAG 聊天请求
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-20
 */
@Data
@Schema(description = "RAG聊天请求")
public class RagChatRequest {

    @Schema(description = "会话ID（首次为空，自动创建）")
    private String sessionId;

    @Schema(description = "关联知识库ID（可选，为空则搜索全部知识库）")
    private String knowledgeBaseId;

    @Schema(description = "用户提问内容")
    private String query;

    @Schema(description = "向量检索返回条数（默认5）")
    private Integer topK;
}
