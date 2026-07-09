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
//update-begin---author:ys ---date:2026-07-09  for：MySQL-ES异步同步-----------
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EsSyncMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String ACTION_INDEX = "INDEX";
    public static final String ACTION_DELETE = "DELETE";

    /** 操作类型: INDEX/DELETE */
    private String action;
    /** 文档 ID */
    private String documentId;
    /** 知识库 ID */
    private String knowledgeBaseId;
}
//update-end---author:ys ---date:2026-07-09  for：MySQL-ES异步同步-----------
