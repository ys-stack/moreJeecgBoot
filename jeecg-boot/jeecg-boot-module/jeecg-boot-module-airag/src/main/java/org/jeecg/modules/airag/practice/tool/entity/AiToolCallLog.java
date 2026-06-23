package org.jeecg.modules.airag.practice.tool.entity;

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
 * AI工具调用日志
 * 记录每次 Tool Calling 的执行过程，用于调试和审计
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-23
 */
@Data
@TableName("ai_tool_call_log")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI工具调用日志")
public class AiToolCallLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    @Schema(description = "关联的聊天会话ID")
    private String sessionId;

    @Schema(description = "关联的消息ID")
    private String messageId;

    @Excel(name = "工具编码", width = 20)
    @Schema(description = "工具编码")
    private String toolCode;

    @Excel(name = "工具名称", width = 20)
    @Schema(description = "工具名称（冗余）")
    private String toolName;

    @Schema(description = "调用入参 JSON")
    private String inputParams;

    @Schema(description = "执行结果 JSON")
    private String outputResult;

    @Excel(name = "状态", width = 12)
    @Schema(description = "执行状态: success / error / timeout")
    private String status;

    @Schema(description = "错误信息")
    private String errorMsg;

    @Excel(name = "耗时(ms)", width = 12)
    @Schema(description = "执行耗时(毫秒)")
    private Integer durationMs;

    @Excel(name = "模型名称", width = 20)
    @Schema(description = "调用的模型名称")
    private String modelName;

    @Schema(description = "创建人")
    private String createBy;

    @Excel(name = "调用时间", width = 20, format = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建日期")
    private Date createTime;
}
