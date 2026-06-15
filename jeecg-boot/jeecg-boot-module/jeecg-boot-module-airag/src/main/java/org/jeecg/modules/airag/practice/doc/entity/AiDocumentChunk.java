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
 * AI文档分片
 * 存储Markdown文档解析后的分片数据，每个分片对应文档中的一个语义段落
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-15
 */
@Data
@TableName("ai_document_chunk")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI文档分片")
public class AiDocumentChunk implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    /** 文档ID（同一次上传的唯一标识） */
    @Excel(name = "文档ID", width = 20)
    @Schema(description = "文档ID")
    private String documentId;

    /** 分片序号（从0开始） */
    @Excel(name = "分片序号", width = 10)
    @Schema(description = "分片序号")
    private Integer chunkIndex;

    /** 所属标题路径，如：概述 > 背景 */
    @Excel(name = "标题路径", width = 30)
    @Schema(description = "所属标题路径")
    private String heading;

    /** 分片内容 */
    @Schema(description = "分片内容")
    private String content;

    /** 预估Token数 */
    @Excel(name = "Token数", width = 10)
    @Schema(description = "预估Token数")
    private Integer tokenCount;

    /** 字符数 */
    @Excel(name = "字符数", width = 10)
    @Schema(description = "字符数")
    private Integer charCount;

    /** 分片类型（heading/text/table/code） */
    @Excel(name = "分片类型", width = 12)
    @Schema(description = "分片类型（heading/text/table/code）")
    private String chunkType;

    /** 扩展元数据JSON */
    @Schema(description = "扩展元数据JSON")
    private String metadata;

    /** 源文件名 */
    @Excel(name = "源文件名", width = 25)
    @Schema(description = "源文件名")
    private String sourceFileName;

    /** 源文件存储路径 */
    @Schema(description = "源文件存储路径")
    private String sourceFilePath;

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
