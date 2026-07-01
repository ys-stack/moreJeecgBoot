package org.jeecg.modules.airag.practice.chat.entity;

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
import java.util.Date;

/**
 * Tool Calling 对话用例
 *
 * 将一个已完成的多轮工具调用会话保存为可复用的测试用例。
 * 记录预期调用的工具和实际调用的工具，用于评测 Agent 的工具选择准确率。
 *
 * 与 ai_chat_session 的关系：一个会话最多保存为一个用例（1:1 可选关联）。
 * 用例创建时会快照会话的关键信息（实际调用了哪些工具），不依赖原始会话消息。
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-29
 */
@Data
@TableName("ai_tool_chat_case")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "Tool Calling 对话用例")
public class AiToolChatCase implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    @Excel(name = "用例名称", width = 30)
    @Schema(description = "用例名称（如：查询超时订单并创建工单）")
    private String caseName;

    @Schema(description = "关联会话ID（快照来源）")
    private String sessionId;

    @Schema(description = "所属用户")
    private String userId;

    @Excel(name = "场景", width = 20)
    @Schema(description = "场景分类: order_query / user_lookup / ticket_create / multi_step")
    private String scenario;

    @Excel(name = "用例描述", width = 40)
    @Schema(description = "用例描述（用户原始提问 + 预期行为）")
    private String description;

    @Excel(name = "预期工具", width = 30)
    @Schema(description = "预期调用的工具（逗号分隔，如 queryOrder,createTicket）")
    private String expectedTools;

    @Excel(name = "实际工具", width = 30)
    @Schema(description = "实际调用的工具（逗号分隔，从会话消息中自动提取）")
    private String actualTools;

    @Excel(name = "是否符合预期", width = 12)
    @Schema(description = "是否符合预期（0=否, 1=是, null=未评测）")
    private Integer isPass;

    // ==================== 审计字段 ====================

    @Schema(description = "创建人")
    private String createBy;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建日期")
    private Date createTime;
}
