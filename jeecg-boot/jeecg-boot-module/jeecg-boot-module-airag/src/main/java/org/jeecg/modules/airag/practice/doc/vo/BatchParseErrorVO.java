package org.jeecg.modules.airag.practice.doc.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量解析错误 VO
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "批量解析错误")
public class BatchParseErrorVO {

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "错误信息")
    private String error;
}
