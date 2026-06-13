package org.jeecg.modules.airag.practice.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * AI 模型调用日志
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-13
 * @Version: V1.0
 */
@Data
@TableName("ai_model_call_log")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI模型调用日志")
public class AiModelCallLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    /** 请求追踪ID */
    @Excel(name = "请求ID", width = 20)
    @Schema(description = "请求追踪ID")
    private String requestId;

    /** 业务类型 */
    @Excel(name = "业务类型", width = 15, dicCode = "ai_biz_type")
    @Schema(description = "业务类型（chat/structured/stream/embedding/rag）")
    private String bizType;

    /** 模型供应商 */
    @Excel(name = "模型供应商", width = 15)
    @Schema(description = "模型供应商")
    private String modelProvider;

    /** 模型名称 */
    @Excel(name = "模型名称", width = 20)
    @Schema(description = "模型名称")
    private String modelName;

    /** 模型版本 */
    @Schema(description = "模型版本")
    private String modelVersion;

    /** 输入token数 */
    @Excel(name = "输入Token", width = 12)
    @Schema(description = "输入token数", example = "0")
    private Integer promptTokens;

    /** 输出token数 */
    @Excel(name = "输出Token", width = 12)
    @Schema(description = "输出token数", example = "0")
    private Integer completionTokens;

    /** 总token数 */
    @Excel(name = "总Token", width = 12)
    @Schema(description = "总token数", example = "0")
    private Integer totalTokens;

    /** 请求内容摘要 */
    @Schema(description = "请求内容摘要")
    private String requestBody;

    /** 响应内容摘要 */
    @Schema(description = "响应内容摘要")
    private String responseBody;

    /** Prompt模板编码 */
    @Schema(description = "Prompt模板编码")
    private String promptCode;

    /** Prompt模板版本 */
    @Schema(description = "Prompt模板版本号", example = "0")
    private Integer promptVersion;

    /** 调用耗时（毫秒） */
    @Excel(name = "耗时(ms)", width = 12)
    @Schema(description = "调用耗时（毫秒）", example = "0")
    private Long durationMs;

    /** 首token耗时（毫秒） */
    @Schema(description = "首token耗时（毫秒）", example = "0")
    private Long firstTokenMs;

    /** 调用状态 */
    @Excel(name = "状态", width = 10, dicCode = "ai_call_status")
    @Schema(description = "调用状态（success/fail/timeout/rate_limit）")
    private String status;

    /** 错误信息 */
    @Schema(description = "错误信息")
    private String errorMsg;

    /** 重试次数 */
    @Schema(description = "重试次数", example = "0")
    private Integer retryCount;

    /** 预估费用 */
    @Excel(name = "预估费用(元)", width = 15)
    @Schema(description = "本次调用预估费用（元）")
    private BigDecimal costEstimate;

    /** 调用用户ID */
    @Schema(description = "调用用户ID")
    private String userId;

    /** 调用用户名 */
    @Excel(name = "调用用户", width = 15)
    @Schema(description = "调用用户名")
    private String userName;

    /** 租户ID */
    @Schema(description = "租户ID")
    private String tenantId;

    /** 客户端IP */
    @Excel(name = "客户端IP", width = 15)
    @Schema(description = "客户端IP")
    private String clientIp;

    /** 请求接口路径 */
    @Excel(name = "接口路径", width = 20)
    @Schema(description = "请求接口路径")
    private String apiPath;

    /** 扩展数据 */
    @Schema(description = "扩展数据（JSON）")
    private String extraData;

    /** 创建人 */
    @Excel(name = "创建人", width = 15)
    @Schema(description = "创建人")
    private String createBy;

    /** 创建时间 */
    @Excel(name = "调用时间", width = 20, format = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private Date createTime;

    /** 所属部门编码 */
    @Schema(description = "所属部门编码")
    private String sysOrgCode;
}
