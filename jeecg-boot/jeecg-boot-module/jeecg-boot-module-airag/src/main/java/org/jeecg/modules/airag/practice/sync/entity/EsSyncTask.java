package org.jeecg.modules.airag.practice.sync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * ES数据同步任务实体类 (Transactional Outbox)
 *
 * @Author: jeecg-boot
 * @Date: 2026-07-10
 */
@Data
@TableName("es_sync_task")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "ES数据同步任务日志表")
public class EsSyncTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务状态常量
     */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    @Schema(description = "操作类型(INDEX/DELETE)")
    private String action;

    @Schema(description = "文档ID")
    private String documentId;

    @Schema(description = "知识库ID")
    private String knowledgeBaseId;

    @Schema(description = "状态(PENDING/SUCCESS/FAILED)")
    private String status;

    @Schema(description = "重试次数")
    private Integer retryCount;

    @Schema(description = "错误日志")
    private String errorMsg;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新时间")
    private Date updateTime;
}
