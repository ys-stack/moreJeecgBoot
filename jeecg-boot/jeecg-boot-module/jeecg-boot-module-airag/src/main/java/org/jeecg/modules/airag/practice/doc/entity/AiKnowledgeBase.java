package org.jeecg.modules.airag.practice.doc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
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
 * AI知识库
 * 一个知识库下包含多个文档，文档解析后产生多个分片
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-15
 */
@Data
@TableName("ai_knowledge_base")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI知识库")
public class AiKnowledgeBase implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    @Excel(name = "知识库名称", width = 20)
    @Schema(description = "知识库名称")
    private String name;

    @Excel(name = "描述", width = 30)
    @Schema(description = "知识库描述")
    private String description;

    @Schema(description = "向量模型ID")
    private String embedModelId;

    @Excel(name = "状态", width = 12)
    @Schema(description = "状态（active/inactive）")
    private String status;

    @Excel(name = "可见角色", width = 20)
    @Schema(description = "可见角色编码（逗号分隔，为空表示所有人可见）")
    private String roleCode;

    @Excel(name = "文档数量", width = 10)
    @Schema(description = "文档数量")
    private Integer docCount;

    @Excel(name = "分片总数", width = 10)
    @Schema(description = "分片总数")
    private Integer chunkCount;

    /**
     * 知识库缓存版本。文档、分片、可见权限或模型配置变化后递增。
     * 答案缓存键包含该版本，因此旧答案不会被新请求命中。
    */
    @Schema(description = "知识库缓存版本")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long cacheVersion;

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
