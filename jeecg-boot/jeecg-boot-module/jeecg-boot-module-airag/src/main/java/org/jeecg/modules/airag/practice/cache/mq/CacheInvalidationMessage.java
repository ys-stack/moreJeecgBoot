package org.jeecg.modules.airag.practice.cache.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheInvalidationMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String TYPE_KNOWLEDGE_CHANGED = "KNOWLEDGE_CHANGED";

    private String eventId;
    private String eventType;
    private String knowledgeBaseId;
    private Long createTime;
}
