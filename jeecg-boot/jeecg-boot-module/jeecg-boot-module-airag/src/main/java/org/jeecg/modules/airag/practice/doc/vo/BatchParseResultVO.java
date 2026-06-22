package org.jeecg.modules.airag.practice.doc.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量解析结果 VO
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "批量解析结果")
public class BatchParseResultVO {

    @Schema(description = "总文件数")
    private Integer totalFiles;

    @Schema(description = "成功数")
    private Integer successCount;

    @Schema(description = "失败数")
    private Integer failedCount;

    @Schema(description = "成功文件的结果列表")
    private List<SingleFileResultVO> results;

    @Schema(description = "失败文件的错误列表")
    private List<BatchParseErrorVO> errors;
}
