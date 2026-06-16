package org.jeecg.modules.airag.practice.vector.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 向量检索结果 VO
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "向量检索结果")
public class VectorSearchResultVO {

    @Schema(description = "分片ID")
    private String chunkId;

    @Schema(description = "文档ID")
    private String documentId;

    @Schema(description = "知识库ID")
    private String knowledgeBaseId;

    @Schema(description = "分片内容")
    private String content;

    @Schema(description = "标题路径")
    private String headingPath;

    @Schema(description = "相似度分数(0~1)")
    private float score;

    @Schema(description = "源文件名")
    private String sourceFileName;

    @Schema(description = "分片序号")
    private Integer chunkIndex;
}
