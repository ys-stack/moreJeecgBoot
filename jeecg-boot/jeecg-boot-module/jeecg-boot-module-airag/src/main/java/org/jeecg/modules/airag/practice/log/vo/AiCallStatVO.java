package org.jeecg.modules.airag.practice.log.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * AI 模型调用统计 VO
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-13
 * @Version: V1.0
 */
@Data
@Schema(description = "AI模型调用统计")
public class AiCallStatVO {

    /** 调用次数 */
    @Schema(description = "调用次数")
    private Long callCount;

    /** 总输入token */
    @Schema(description = "总输入token")
    private Long totalPromptTokens;

    /** 总输出token */
    @Schema(description = "总输出token")
    private Long totalCompletionTokens;

    /** 总token */
    @Schema(description = "总token")
    private Long totalTokens;

    /** 平均耗时（毫秒） */
    @Schema(description = "平均耗时（毫秒）")
    private BigDecimal avgDurationMs;

    /** 最大耗时（毫秒） */
    @Schema(description = "最大耗时（毫秒）")
    private Long maxDurationMs;

    /** 最小耗时（毫秒） */
    @Schema(description = "最小耗时（毫秒）")
    private Long minDurationMs;

    /** 成功次数 */
    @Schema(description = "成功次数")
    private Long successCount;

    /** 失败次数 */
    @Schema(description = "失败次数")
    private Long failCount;

    /** 总预估费用（元） */
    @Schema(description = "总预估费用（元）")
    private BigDecimal totalCost;

    /** 统计日期 */
    @Schema(description = "统计日期")
    private String statDate;

    /** 模型名称 */
    @Schema(description = "模型名称")
    private String modelName;
}
