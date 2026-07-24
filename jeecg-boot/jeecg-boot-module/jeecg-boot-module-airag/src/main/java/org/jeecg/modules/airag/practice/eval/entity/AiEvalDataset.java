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
 * AI 评测集（黄金测试用例）实体类。
 * 对应数据库表 `ai_eval_dataset`，用于存储事先标注好的标准测试用例（RAG 知识库用例与 Agent 工具调用用例）。
 */
@Data
@TableName("ai_eval_dataset")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI评测集测试用例表")
public class AiEvalDataset implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID：数据库唯一标识，使用雪花算法自动生成 */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    /** 用例编码：全局唯一业务代号，例如 RAG_001 (RAG用例)、AGENT_001 (Agent用例) */
    @Excel(name = "用例编码", width = 20)
    @Schema(description = "用例唯一编码")
    private String caseCode;

    /** 用例名称：简短描述本条用例的测试点，例如“Redis持久化AOF与RDB对比” */
    @Excel(name = "用例名称", width = 30)
    @Schema(description = "用例展示名称")
    private String caseName;

    /** 评测类型：rag (知识库问答评测) 或 agent (Tool Calling工具调用Agent评测) */
    @Excel(name = "评测类型", width = 12)
    @Schema(description = "评测类型：rag / agent")
    private String evalType;

    /** 业务场景分类：例如 qa(基础问答)、refusal(防幻觉拒答)、order(订单工具)、ticket(工单创建) */
    @Excel(name = "业务场景", width = 20)
    @Schema(description = "业务场景")
    private String scenario;

    /** 用户输入问题：评测执行时发送给 RAG 系统或 Agent 的原始提示问题文本 */
    @Schema(description = "评测时发送给模型的用户问题")
    private String question;

    /** [RAG专用] 知识库ID：本条测试用例绑定的具体知识库ID；为空时表示搜索用户当前所有可访问知识库 */
    @Schema(description = "RAG用例指定的知识库ID")
    private String knowledgeBaseId;

    /** [RAG专用] 预期标准答案：人工标注的标准答案或主要参考解答要点 */
    @Schema(description = "预期标准答案或答案要点")
    private String expectedAnswer;

    /** [RAG专用] 预期关键词：JSON格式，可为一维数组 `[\"RDB\",\"AOF\"]` 或二维同义词组 `[[\"RDB\",\"快照\"],[\"AOF\",\"日志\"]]` */
    @Schema(description = "预期关键词JSON数组")
    private String expectedKeywords;

    /** [RAG专用] 预期引用文档：JSON格式数组，期望 RAG 检索并引用的文档名或 chunkId，例如 `[\"Redis面试文档.md\"]` */
    @Schema(description = "预期引用JSON数组")
    private String expectedReferences;

    /** [RAG专用] 是否预期拒答：0=应正常回答，1=应明确拒答（用于知识库外问题、敏感词或防硬编幻觉测试） */
    @Schema(description = "是否预期拒答：0否 1是")
    private Integer expectedReject;

    /** [Agent专用] 预期调用工具编码：期望 Agent 模型选中的目标工具编码，如 `queryOrder`, `createTicket` */
    @Schema(description = "Agent预期调用工具编码")
    private String expectedToolName;

    /** [Agent专用] 预期工具参数：JSON字符串，模型抽取的工具入参，如 `{\"orderNo\":\"ORD-20260701\"}` */
    @Schema(description = "Agent预期工具参数JSON")
    private String expectedToolParams;

    /** [Agent专用] 预期任务结果：Agent 执行完工具后的期望输出关键词或状态校验点 */
    @Schema(description = "Agent预期任务结果或校验点")
    private String expectedTaskResult;

    /** 用例难度等级：easy(简单)、normal(普通)、hard(困难) */
    @Excel(name = "难度", width = 12)
    @Schema(description = "难度：easy / normal / hard")
    private String difficulty;

    /** 用例权重：默认 1.00，计算整体加权平均分时，高权重用例占比更高 */
    @Schema(description = "用例权重(用于加权平均分)")
    private BigDecimal weight;

    /** 用例启用状态：0=禁用(不参与批量评测)，1=启用(正常参与评测) */
    @Excel(name = "状态", width = 10)
    @Schema(description = "状态：0禁用 1启用")
    private Integer status;

    /** 备注说明：记录用例设计背景或维护说明 */
    @Schema(description = "备注说明")
    private String remark;

    /** 创建人账号 */
    @Schema(description = "创建人账号")
    private String createBy;

    /** 创建时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private Date createTime;

    /** 更新人账号 */
    @Schema(description = "更新人账号")
    private String updateBy;

    /** 更新时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间")
    private Date updateTime;

    /** 所属部门编码 */
    @Schema(description = "所属部门编码")
    private String sysOrgCode;

    /** 多租户隔离ID */
    @Schema(description = "租户ID")
    private String tenantId;
}

