package org.jeecg.modules.airag.practice.doc.entity;

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
 * AI文档
 * 属于某个知识库，解析后产生多个分片（AiDocumentChunk）
 *
 * 生命周期：pending → parsing → completed / failed
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-15
 */
@Data
@TableName("ai_document")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI文档")
public class AiDocument implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    @Excel(name = "知识库ID", width = 20)
    @Schema(description = "所属知识库ID")
    private String knowledgeBaseId;

    @Excel(name = "文档标题", width = 25)
    @Schema(description = "文档标题")
    private String title;

    @Excel(name = "文档类型", width = 12)
    @Schema(description = "文档类型（markdown/pdf/txt/docx）")
    private String docType;

    @Excel(name = "原始文件名", width = 25)
    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "文件存储路径")
    private String filePath;

    @Excel(name = "文件大小", width = 12)
    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Excel(name = "状态", width = 12)
    @Schema(description = "状态（pending/parsing/completed/failed）")
    private String status;

    @Excel(name = "分片数量", width = 10)
    @Schema(description = "分片数量")
    private Integer chunkCount;

    @Excel(name = "总字符数", width = 12)
    @Schema(description = "文档总字符数")
    private Integer totalChars;

    @Schema(description = "解析失败时的错误信息")
    private String errorMsg;

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
