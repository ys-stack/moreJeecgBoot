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
 * AI工具定义
 * 存储 Tool Calling 的工具元数据，供模型 Function Calling 使用
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-23
 */
@Data
@TableName("ai_tool_definition")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI工具定义")
public class AiToolDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    @Excel(name = "工具编码", width = 20)
    @Schema(description = "工具编码（唯一标识，发送给模型）")
    private String toolCode;

    @Excel(name = "工具名称", width = 20)
    @Schema(description = "工具名称（中文显示名）")
    private String toolName;

    @Excel(name = "描述", width = 40)
    @Schema(description = "工具描述（发送给模型，帮助模型决定何时调用）")
    private String description;

    @Schema(description = "参数 JSON Schema（OpenAI function calling 格式）")
    private String parametersSchema;

    @Excel(name = "端点类型", width = 15)
    @Schema(description = "端点类型: JAVA_BEAN / REST_API / SQL_QUERY")
    private String endpointType;

    @Schema(description = "处理器引用（Bean名 / URL / SQL模板ID）")
    private String handlerRef;

    @Excel(name = "分类", width = 15)
    @Schema(description = "工具分类: query / write / notify / system")
    private String category;

    @Excel(name = "状态", width = 12)
    @Schema(description = "状态: active / inactive")
    private String status;

    @Schema(description = "是否只读操作（0=否,1=是）")
    private Integer isReadOnly;

    @Schema(description = "执行超时时间(毫秒)")
    private Integer timeoutMs;

    @Schema(description = "写操作是否需要用户确认（0=否,1=是）")
    private Integer requireConfirm;

    @Schema(description = "排序号")
    private Integer sortOrder;

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
}
