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
 * AI对话会话
 * 记录一次对话会话的元信息，关联用户和知识库。
 * 一个会话包含多条消息（AiChatMessage）。
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-20
 */
@Data
@TableName("ai_chat_session")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI对话会话")
public class AiChatSession implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    @Excel(name = "会话标题", width = 30)
    @Schema(description = "会话标题（自动取首条用户消息摘要）")
    private String title;

    @Schema(description = "所属用户ID")
    private String userId;

    @Excel(name = "关联知识库", width = 20)
    @Schema(description = "关联知识库ID（可选）")
    private String knowledgeBaseId;

    @Excel(name = "模型供应商", width = 15)
    @Schema(description = "模型供应商")
    private String modelProvider;

    @Excel(name = "模型名称", width = 20)
    @Schema(description = "使用的模型名称")
    private String modelName;

    @Excel(name = "状态", width = 10)
    @Schema(description = "状态：active-活跃 / archived-已归档")
    private String status;

    @Excel(name = "消息数量", width = 10)
    @Schema(description = "消息数量")
    private Integer messageCount;

    @Schema(description = "扩展元数据JSON")
    private String metadata;

    // ==================== 审计字段 ====================

    @Schema(description = "创建人")
    private String createBy;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建日期")
    private Date createTime;

    @Schema(description = "更新人")
    private String updateBy;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新日期")
    private Date updateTime;

    @Schema(description = "所属部门")
    private String sysOrgCode;

    @Schema(description = "租户id")
    private String tenantId;
}
