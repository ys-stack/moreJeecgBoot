package org.jeecg.modules.airag.practice.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

/** 一次评测运行的持久化状态与配置快照。 */
@Data
@Accessors(chain = true)
@TableName("ai_eval_run")
@Schema(description = "AI评测运行记录")
public class AiEvalRun implements Serializable {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";

    @TableId(type = IdType.INPUT)
    private String id;
    private String runName;
    private String status;
    private String evalType;
    private String promptCode;
    private Integer promptVersion;
    private String modelProvider;
    private String modelName;
    private String requestJson;
    private String caseSnapshot;
    private Integer totalCases;
    private Integer processedCases;
    private Integer passedCases;
    private String currentCaseCode;
    private String errorMsg;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    private String createBy;
    private Date createTime;
    private Date updateTime;
    private String tenantId;
}
