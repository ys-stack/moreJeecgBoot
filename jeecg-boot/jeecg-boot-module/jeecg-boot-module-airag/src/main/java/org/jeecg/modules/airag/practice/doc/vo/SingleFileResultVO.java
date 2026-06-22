package org.jeecg.modules.airag.practice.doc.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单文件解析结果 VO
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "单文件解析结果")
public class SingleFileResultVO {

    @Schema(description = "文档ID")
    private String documentId;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件类型")
    private String fileType;

    @Schema(description = "总字符数")
    private Integer totalChars;

    @Schema(description = "分片数量")
    private Integer chunkCount;

    @Schema(description = "预估Token总数")
    private Integer totalTokens;

    @Schema(description = "分片预览（前20条）")
    private List<DocumentChunkVO> chunks;
}
