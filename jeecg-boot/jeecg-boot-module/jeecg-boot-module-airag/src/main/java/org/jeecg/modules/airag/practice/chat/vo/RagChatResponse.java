package org.jeecg.modules.airag.practice.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jeecg.modules.airag.practice.vector.vo.VectorSearchResultVO;

import java.util.List;

/**
 * RAG 聊天响应
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "RAG聊天响应")
public class RagChatResponse {

    @Schema(description = "会话ID")
    private String sessionId;

    @Schema(description = "用户消息ID")
    private String userMessageId;

    @Schema(description = "AI回答消息ID")
    private String assistantMessageId;

    @Schema(description = "AI回答内容")
    private String answer;

    @Schema(description = "参考来源（向量检索命中的chunk）")
    private List<ReferenceChunk> references;

    @Schema(description = "使用的模型名称")
    private String model;

    @Schema(description = "模型响应耗时(毫秒)")
    private Long durationMs;

    @Schema(description = "Prompt token数")
    private Integer promptTokens;

    @Schema(description = "Completion token数")
    private Integer completionTokens;

    /**
     * 参考来源的单个 chunk 信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "参考来源chunk")
    public static class ReferenceChunk {
        @Schema(description = "分片ID")
        private String chunkId;

        @Schema(description = "分片内容摘要")
        private String content;

        @Schema(description = "来源文件名")
        private String sourceFileName;

        @Schema(description = "标题路径")
        private String headingPath;

        @Schema(description = "相似度得分(0~1)")
        private Float score;
    }

    /**
     * 将 VectorSearchResultVO 列表转换为 ReferenceChunk 列表
     */
    public static List<ReferenceChunk> fromSearchResults(List<VectorSearchResultVO> results) {
        if (results == null) {
            return List.of();
        }
        return results.stream().map(r -> ReferenceChunk.builder()
                .chunkId(r.getChunkId())
                .content(truncate(r.getContent(), 200))
                .sourceFileName(r.getSourceFileName())
                .headingPath(r.getHeadingPath())
                .score(r.getScore())
                .build()).toList();
    }

    /** 截断文本，避免响应体过大 */
    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
