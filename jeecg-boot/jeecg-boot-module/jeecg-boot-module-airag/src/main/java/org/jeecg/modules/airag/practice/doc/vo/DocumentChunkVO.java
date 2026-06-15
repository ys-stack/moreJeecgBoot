package org.jeecg.modules.airag.practice.doc.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档分片VO —— 解析器产出的中间结构，也是返回给前端的数据载体
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文档分片VO")
public class DocumentChunkVO {

    @Schema(description = "分片序号")
    private Integer chunkIndex;

    @Schema(description = "所属标题路径，如：概述 > 背景")
    private String heading;

    @Schema(description = "分片内容")
    private String content;

    @Schema(description = "字符数")
    private Integer charCount;

    @Schema(description = "预估Token数（按 1 token ≈ 1.5 个中文字估算）")
    private Integer tokenCount;

    @Schema(description = "分片类型（heading/text/table/code）")
    private String chunkType;
}
