package org.jeecg.modules.airag.practice.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * AI对话消息
 * 记录会话中的每条消息（用户提问 / AI回答 / 系统消息）。
 * 属于某个会话（AiChatSession），不设置 updateBy/updateTime（消息不可修改）。
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-20
 */
@Data
@TableName("ai_chat_message")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(description = "AI对话消息")
public class AiChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private String id;

    @Schema(description = "所属会话ID")
    private String sessionId;

    @Schema(description = "父消息ID（用于回复链）")
    private String parentMessageId;

    @Schema(description = "角色：user-用户 / assistant-AI / system-系统")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "Prompt token数")
    private Integer promptTokens;

    @Schema(description = "Completion token数")
    private Integer completionTokens;

    @Schema(description = "总 token数")
    private Integer totalTokens;

    @Schema(description = "RAG 检索到的上下文(JSON)")
    private String ragContext;

    @Schema(description = "RAG 检索到的分片数量")
    private Integer ragChunkCount;

    @Schema(description = "实际使用的模型供应商")
    private String modelProvider;

    @Schema(description = "实际使用的模型名称")
    private String modelName;

    @Schema(description = "模型响应耗时(毫秒)")
    private Long durationMs;

    @Schema(description = "状态：success-成功 / error-失败")
    private String status;

    @Schema(description = "错误信息")
    private String errorMsg;

    // ==================== 审计字段 ====================

    @Schema(description = "创建人")
    private String createBy;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建日期")
    private Date createTime;

    @Schema(description = "工具调用详情JSON")
    private String toolCalls;

}
