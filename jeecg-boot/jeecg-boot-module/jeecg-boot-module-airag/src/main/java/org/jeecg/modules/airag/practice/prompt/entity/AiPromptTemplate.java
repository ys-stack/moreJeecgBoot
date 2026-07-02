package org.jeecg.modules.airag.practice.prompt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.jeecgframework.poi.excel.annotation.Excel;

import java.io.Serializable;
import java.util.Date;

/**
 * AI Prompt 模板表
 *
 * 学习笔记：
 * - @TableName 指定表名，MyBatis-Plus 会自动把驼峰转下划线
 * - @TableId(type = IdType.ASSIGN_ID) 表示用雪花算法自动生成主键
 * - JeecgBoot 标准审计字段：createBy/createTime/updateBy/updateTime/sysOrgCode/tenantId
 */
@Data
@TableName("ai_prompt_template")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@Schema(description = "AI Prompt 模板")
public class AiPromptTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    @Excel(name = "模板编码", width = 20)
    @Schema(description = "模板编码（唯一标识，如 structured_analysis）")
    private String promptCode;

    @Excel(name = "版本号", width = 10)
    @Schema(description = "版本号")
    private Integer version;

    @Schema(description = "Prompt 模板内容")
    private String template;

    @Schema(description = "模板变量列表（JSON 数组）")
    private String variables;

    @Excel(name = "说明", width = 30)
    @Schema(description = "模板用途说明")
    private String description;

    @Schema(description = "变更说明（记录本次修改的原因和内容）")
    private String changeLog;

    @Excel(name = "状态", width = 10, dicCode = "status")
    @Schema(description = "状态：0=禁用 1=启用")
    private Integer status;

    @Schema(description = "创建人")
    private String createBy;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新人")
    private String updateBy;

    @Schema(description = "更新时间")
    private Date updateTime;

    @Schema(description = "所属部门编码")
    private String sysOrgCode;

    @Schema(description = "租户ID")
    private String tenantId;
}
