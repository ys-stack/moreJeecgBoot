package org.jeecg.modules.airag.practice.doc.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档上传结果VO —— 上传接口返回的统计信息
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文档上传结果")
public class DocumentUploadResultVO {

    @Schema(description = "知识库ID")
    private String knowledgeBaseId;

    @Schema(description = "文档ID（用于后续查询/删除分片）")
    private String documentId;

    @Schema(description = "源文件名")
    private String fileName;

    @Schema(description = "文件字符总数")
    private Integer totalChars;

    @Schema(description = "分片总数")
    private Integer chunkCount;

    @Schema(description = "预估Token总数")
    private Integer totalTokens;

    @Schema(description = "文件存储路径")
    private String filePath;

    @Schema(description = "分片预览（前5条）")
    private List<DocumentChunkVO> chunks;
}
