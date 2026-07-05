package org.jeecg.modules.airag.practice.eval.entity;

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
 * AI评测结果实体。
 * 用于保存一次评测运行中每个用例的执行结果和评分。
 */
@Data
@TableName("ai_eval_result")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI评测结果")
public class AiEvalResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID，使用雪花ID自动生成 */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    /** 评测运行ID，同一次批量评测共用一个runId */
    @Excel(name = "运行ID", width = 24)
    @Schema(description = "评测运行ID")
    private String runId;

    /** 评测运行名称，用于页面展示和对比 */
    @Excel(name = "运行名称", width = 30)
    @Schema(description = "评测运行名称")
    private String runName;

    /** 关联的评测用例ID */
    @Schema(description = "评测用例ID")
    private String datasetId;

    /** 用例编码快照，避免用例后续修改影响历史结果 */
    @Excel(name = "用例编码", width = 20)
    @Schema(description = "用例编码快照")
    private String caseCode;

    /** 评测类型：rag 或 agent */
    @Excel(name = "评测类型", width = 12)
    @Schema(description = "评测类型：rag / agent")
    private String evalType;

    /** 本次评测使用的Prompt编码 */
    @Schema(description = "Prompt编码")
    private String promptCode;

    /** 本次评测使用的Prompt版本 */
    @Schema(description = "Prompt版本")
    private Integer promptVersion;

    /** 本次评测使用的模型供应商 */
    @Schema(description = "模型供应商")
    private String modelProvider;

    /** 本次评测使用的模型名称 */
    @Excel(name = "模型名称", width = 20)
    @Schema(description = "模型名称")
    private String modelName;

    /** 用户问题快照 */
    @Schema(description = "用户输入问题快照")
    private String question;

    /** 模型实际回答文本 */
    @Schema(description = "模型实际回答")
    private String actualAnswer;

    /** RAG实际引用来源，JSON格式 */
    @Schema(description = "RAG实际引用JSON")
    private String actualReferences;

    /** Agent实际工具调用详情，JSON格式 */
    @Schema(description = "Agent实际工具调用JSON")
    private String actualToolCalls;

    /** 原始响应JSON，便于排查和复盘 */
    @Schema(description = "原始响应JSON")
    private String rawResponse;

    /** RAG回答相关性得分，0-100 */
    @Schema(description = "RAG回答相关性得分")
    private BigDecimal answerRelevanceScore;

    /** RAG引用命中得分，0-100 */
    @Schema(description = "RAG引用命中得分")
    private BigDecimal referenceHitScore;

    /** RAG拒答得分，0-100 */
    @Schema(description = "RAG拒答得分")
    private BigDecimal rejectScore;

    /** Agent工具选择得分，0-100 */
    @Schema(description = "Agent工具选择得分")
    private BigDecimal toolSelectionScore;

    /** Agent工具参数准确得分，0-100 */
    @Schema(description = "Agent参数准确得分")
    private BigDecimal paramAccuracyScore;

    /** Agent任务完成得分，0-100 */
    @Schema(description = "Agent任务完成得分")
    private BigDecimal taskCompletionScore;

    /** 单用例综合得分，0-100 */
    @Excel(name = "综合得分", width = 12)
    @Schema(description = "综合得分")
    private BigDecimal totalScore;

    /** 是否通过阈值判断：0否，1是 */
    @Excel(name = "是否通过", width = 10)
    @Schema(description = "是否通过：0否 1是")
    private Integer passed;

    /** 单用例执行耗时，单位毫秒 */
    @Excel(name = "耗时(ms)", width = 12)
    @Schema(description = "单用例耗时，毫秒")
    private Long durationMs;

    /** 输入token数量 */
    @Schema(description = "输入token数")
    private Integer promptTokens;

    /** 输出token数量 */
    @Schema(description = "输出token数")
    private Integer completionTokens;

    /** 总token数量 */
    @Schema(description = "总token数")
    private Integer totalTokens;

    /** 执行状态：success、fail、error、skipped */
    @Excel(name = "执行状态", width = 12)
    @Schema(description = "执行状态：success / fail / error / skipped")
    private String status;

    /** 异常信息或失败原因 */
    @Schema(description = "错误信息")
    private String errorMsg;

    /** 评分细节JSON，记录各指标命中情况 */
    @Schema(description = "评分明细JSON")
    private String judgeDetail;

    /** 创建人 */
    @Schema(description = "创建人")
    private String createBy;

    /** 创建时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private Date createTime;

    /** 所属部门编码 */
    @Schema(description = "所属部门编码")
    private String sysOrgCode;

    /** 租户ID */
    @Schema(description = "租户ID")
    private String tenantId;
}
