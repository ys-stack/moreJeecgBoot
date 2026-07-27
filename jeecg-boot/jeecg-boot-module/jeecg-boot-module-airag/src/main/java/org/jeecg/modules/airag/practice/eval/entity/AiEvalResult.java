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
 * AI 评测结果实体类。
 * 对应数据库表 `ai_eval_result`，用于记录某次评测运行（runId）中单条测试用例的实际运行结果、详细评分及 Token 消耗。
 */
@Data
@TableName("ai_eval_result")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI评测结果记录表")
public class AiEvalResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID：数据库唯一标识，使用雪花算法自动生成 */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    /** 评测运行ID：一次批量评测对应唯一的UUID编码，同一次发起的用例共享同一个 runId */
    @Excel(name = "运行ID", width = 24)
    @Schema(description = "评测运行ID")
    private String runId;

    /** 评测运行名称：用于前端大盘和趋势图展示，如“Prompt-V2基线评测” */
    @Excel(name = "运行名称", width = 30)
    @Schema(description = "评测运行名称")
    private String runName;

    /** 关联的用例ID：对应 `ai_eval_dataset` 表的主键 id */
    @Schema(description = "评测用例ID")
    private String datasetId;

    /** 用例编码快照：例如 RAG_001，保存运行时的编码，防止后续修改用例导致历史记录失效 */
    @Excel(name = "用例编码", width = 20)
    @Schema(description = "用例编码快照")
    private String caseCode;

    /** 评测类型：rag (知识库问答) 或 agent (Tool Calling工具调用) */
    @Excel(name = "评测类型", width = 12)
    @Schema(description = "评测类型：rag / agent")
    private String evalType;

    /** 本次评测绑定的 Prompt 模板编码，用于追溯评测效果 */
    @Schema(description = "Prompt编码")
    private String promptCode;

    /** 本次评测绑定的 Prompt 模板版本号 */
    @Schema(description = "Prompt版本号")
    private Integer promptVersion;

    /** 本次评测调用的模型供应商，例如 deepseek、siliconflow */
    @Schema(description = "模型供应商")
    private String modelProvider;

    /** 本次评测调用的模型名称，例如 deepseek-chat、qwen2.5-72b */
    @Excel(name = "模型名称", width = 20)
    @Schema(description = "模型名称")
    private String modelName;

    /** 用户问题快照：发送给模型的原始提问 */
    @Schema(description = "用户输入问题快照")
    private String question;

    /** 执行时的用例权重快照，避免用例调整导致历史报告漂移 */
    private BigDecimal caseWeight;

    /** 模型实际回答：模型在评测时返回的最终文本解答 */
    @Schema(description = "模型实际回答文本")
    private String actualAnswer;

    /** [RAG专用] 实际引用来源：JSON格式，记录 RAG 检索到的参考段落与引用文档信息 */
    @Schema(description = "RAG实际引用JSON")
    private String actualReferences;

    /** [Agent专用] 实际工具调用：JSON格式，记录 Agent 模型决策调用的工具名称及入参详情 */
    @Schema(description = "Agent实际工具调用JSON")
    private String actualToolCalls;

    /** 原始响应 JSON 快照：保留 HTTP 或 SDK 返回的完整响应对象，便于后期排查与分析 */
    @Schema(description = "原始响应JSON")
    private String rawResponse;

    /** [RAG专用] 回答相关性得分：0.00 ~ 100.00，衡量回答是否命中预期关键词 */
    @Schema(description = "RAG回答相关性得分(0-100)")
    private BigDecimal answerRelevanceScore;

    /** [RAG专用] 引用命中得分：0.00 ~ 100.00，衡量检索引用的文档是否符合预期 */
    @Schema(description = "RAG引用命中得分(0-100)")
    private BigDecimal referenceHitScore;

    /** RAG召回片段关键词命中得分 */
    private BigDecimal chunkHitScore;

    /** [RAG专用] 防幻觉拒答得分：0.00 ~ 100.00，衡量知识库无答案/敏感词时是否正确拒答 */
    @Schema(description = "RAG拒答得分(0-100)")
    private BigDecimal rejectScore;

    /** [Agent专用] 工具选择得分：0.00 ~ 100.00，衡量模型是否正确选定了预期目标工具 */
    @Schema(description = "Agent工具选择得分(0-100)")
    private BigDecimal toolSelectionScore;

    /** [Agent专用] 参数准确得分：0.00 ~ 100.00，衡量模型抽取的工具入参与预期的吻合度 */
    @Schema(description = "Agent参数准确得分(0-100)")
    private BigDecimal paramAccuracyScore;

    /** [Agent专用] 任务完成得分：0.00 ~ 100.00，衡量工具执行后最终回复是否达成诉求 */
    @Schema(description = "Agent任务完成得分(0-100)")
    private BigDecimal taskCompletionScore;

    /** Agent是否正确触发或避免二次确认 */
    private BigDecimal confirmationScore;

    /** 本条用例综合得分：0.00 ~ 100.00，由各子项指标按比例加权计算得出 */
    @Excel(name = "综合得分", width = 12)
    @Schema(description = "综合得分(0-100)")
    private BigDecimal totalScore;

    /** 是否通过考核：0=未通过，1=通过（综合得分达到门槛值，如70分） */
    @Excel(name = "是否通过", width = 10)
    @Schema(description = "是否通过：0否 1是")
    private Integer passed;

    /** 单用例执行耗时：单位毫秒(ms)，从发起请求到收到完整回答的消耗时间 */
    @Excel(name = "耗时(ms)", width = 12)
    @Schema(description = "单用例耗时(毫秒)")
    private Long durationMs;

    /** 输入 Token 消耗量：发送 Prompt 与上下文消耗的 Token 数 */
    @Schema(description = "输入token数")
    private Integer promptTokens;

    /** 输出 Token 消耗量：模型生成文本解答消耗的 Token 数 */
    @Schema(description = "输出token数")
    private Integer completionTokens;

    /** 总 Token 消耗量：promptTokens + completionTokens */
    @Schema(description = "总token数")
    private Integer totalTokens;

    /** 执行状态：success(执行成功), fail(得分未达标), error(系统运行异常), skipped(跳过) */
    @Excel(name = "执行状态", width = 12)
    @Schema(description = "执行状态：success / fail / error / skipped")
    private String status;

    /** 错误信息：单条用例执行抛出异常时的日志报错堆栈摘要 */
    @Schema(description = "错误信息")
    private String errorMsg;

    /** 评分明细 JSON：记录各维度得分以及比对过程的详细 JSON 数据 */
    @Schema(description = "评分明细JSON")
    private String judgeDetail;

    /** 创建人账号 */
    @Schema(description = "创建人账号")
    private String createBy;

    /** 创建时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private Date createTime;

    /** 所属部门编码 */
    @Schema(description = "所属部门编码")
    private String sysOrgCode;

    /** 多租户隔离ID */
    @Schema(description = "租户ID")
    private String tenantId;
}

