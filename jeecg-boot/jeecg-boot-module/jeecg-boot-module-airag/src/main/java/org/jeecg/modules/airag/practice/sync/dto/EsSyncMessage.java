package org.jeecg.modules.airag.practice.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

/**
 * ES 异步同步消息对象
 *
 * @Author: jeecg-boot
 * @Date: 2026-07-09
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EsSyncMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String ACTION_INDEX = "INDEX";
    public static final String ACTION_DELETE = "DELETE";

    /** Outbox 任务 ID，用于精确确认和失败重试 */
    private String taskId;
    /** 操作类型: INDEX/DELETE */
    private String action;
    /** 文档 ID */
    private String documentId;
    /** 知识库 ID */
    private String knowledgeBaseId;
}
