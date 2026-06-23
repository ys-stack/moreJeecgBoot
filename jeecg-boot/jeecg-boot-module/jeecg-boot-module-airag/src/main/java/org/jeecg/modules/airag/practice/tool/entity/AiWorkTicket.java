package org.jeecg.modules.airag.practice.tool.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * 工单实体（对应 ai_work_ticket 表）
 */
@Data
@TableName("ai_work_ticket")
public class AiWorkTicket implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 工单编号（自动生成，如 TK202606220001） */
    private String ticketNo;

    /** 工单标题 */
    private String title;

    /** 工单描述 */
    private String description;

    /** 工单类型: bug / feature / task / question */
    private String ticketType;

    /** 优先级: low / medium / high / urgent */
    private String priority;

    /** 状态: open / in_progress / resolved / closed */
    private String status;

    /** 处理人 */
    private String assignee;

    /** 提交人 */
    private String requester;

    /** 处理结果 */
    private String resolution;

    private String createBy;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    private String updateBy;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
