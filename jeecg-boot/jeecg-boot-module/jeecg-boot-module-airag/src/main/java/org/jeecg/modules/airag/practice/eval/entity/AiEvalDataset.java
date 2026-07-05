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
 * AI评测集实体。
 * 用于保存RAG和Agent的标准测试用例。
 */
@Data
@TableName("ai_eval_dataset")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI评测集")
public class AiEvalDataset implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID，使用雪花ID自动生成 */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    /** 用例唯一编码，如RAG_001、AGENT_001 */
    @Excel(name = "用例编码", width = 20)
    @Schema(description = "用例编码")
    private String caseCode;

    /** 用例展示名称，方便页面识别 */
    @Excel(name = "用例名称", width = 30)
    @Schema(description = "用例名称")
    private String caseName;

    /** 评测类型：rag 或 agent */
    @Excel(name = "评测类型", width = 12)
    @Schema(description = "评测类型：rag / agent")
    private String evalType;

    /** 业务场景标签，如qa、refusal、order、ticket */
    @Excel(name = "业务场景", width = 20)
    @Schema(description = "业务场景")
    private String scenario;

    /** 评测时发送给模型的用户问题 */
    @Schema(description = "用户输入问题")
    private String question;

    /** RAG评测指定的知识库ID，空表示使用默认可访问知识库 */
    @Schema(description = "RAG用例指定的知识库ID")
    private String knowledgeBaseId;

    /** 预期答案或人工整理的答案要点 */
    @Schema(description = "预期答案或答案要点")
    private String expectedAnswer;

    /** 预期答案关键词，JSON数组格式 */
    @Schema(description = "预期关键词JSON数组")
    private String expectedKeywords;

    /** 预期引用来源，JSON数组格式，可存chunkId、docId或文件名 */
    @Schema(description = "预期引用JSON数组")
    private String expectedReferences;

    /** 是否期望模型拒答：0否，1是 */
    @Schema(description = "是否预期拒答：0否 1是")
    private Integer expectedReject;

    /** Agent评测期望调用的工具编码 */
    @Schema(description = "Agent预期调用工具编码")
    private String expectedToolName;

    /** Agent评测期望工具入参，JSON对象格式 */
    @Schema(description = "Agent预期工具参数JSON")
    private String expectedToolParams;

    /** Agent评测期望任务结果或关键校验点 */
    @Schema(description = "Agent预期任务结果或校验点")
    private String expectedTaskResult;

    /** 用例难度：easy、normal、hard */
    @Excel(name = "难度", width = 12)
    @Schema(description = "难度：easy / normal / hard")
    private String difficulty;

    /** 用例权重，用于报告综合评分 */
    @Schema(description = "用例权重")
    private BigDecimal weight;

    /** 用例状态：0禁用，1启用 */
    @Excel(name = "状态", width = 10)
    @Schema(description = "状态：0禁用 1启用")
    private Integer status;

    /** 备注说明，记录用例设计背景 */
    @Schema(description = "备注")
    private String remark;

    /** 创建人 */
    @Schema(description = "创建人")
    private String createBy;

    /** 创建时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private Date createTime;

    /** 更新人 */
    @Schema(description = "更新人")
    private String updateBy;

    /** 更新时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间")
    private Date updateTime;

    /** 所属部门编码 */
    @Schema(description = "所属部门编码")
    private String sysOrgCode;

    /** 租户ID */
    @Schema(description = "租户ID")
    private String tenantId;
}
