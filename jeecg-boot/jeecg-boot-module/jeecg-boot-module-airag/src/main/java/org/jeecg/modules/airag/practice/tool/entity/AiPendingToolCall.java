package org.jeecg.modules.airag.practice.tool.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 写工具服务端确认单。
 * 保存模型首次生成的精确参数，并绑定用户、会话和过期时间，防止客户端篡改或重放。
 */
@Data
@Accessors(chain = true)
@TableName("ai_pending_tool_call")
public class AiPendingToolCall implements Serializable {
    /** 确认单主键，同时作为前端确认时提交的不可预测标识。 */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** 关联会话 ID。 */
    private String sessionId;
    /** 关联用户消息 ID。 */
    private String messageId;
    /** 工具定义 ID。 */
    private String toolId;
    /** 工具编码。 */
    private String toolCode;
    /** 模型首次生成并由服务端保存的精确参数 JSON。 */
    private String argumentsJson;
    /** 参数 JSON 的 SHA-256，用于执行前完整性校验。 */
    private String argumentsHash;
    /** 发起确认的用户 ID。 */
    private String userId;
    /** PENDING、EXECUTING、EXECUTED、CANCELLED、FAILED。 */
    private String status;
    /** 确认单过期时间。 */
    private Date expiresAt;
    /** 实际执行时间。 */
    private Date executedAt;
    /** 工具执行结果，超长内容会截断。 */
    private String outputResult;
    /** 工具执行失败摘要。 */
    private String errorMsg;
    /** 创建时间。 */
    private Date createTime;
    /** 最后更新时间。 */
    private Date updateTime;
}
