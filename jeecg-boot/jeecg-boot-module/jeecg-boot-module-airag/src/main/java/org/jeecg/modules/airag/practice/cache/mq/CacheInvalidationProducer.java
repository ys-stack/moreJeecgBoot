package org.jeecg.modules.airag.practice.cache.mq;

import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 缓存失效事件生产者。
 *
 * <p>使用 ActiveMQ Virtual Topic，让每个应用实例通过独立 Queue 收到同一事件。</p>
 */
@Slf4j
@Component
public class CacheInvalidationProducer {

    public static final String VIRTUAL_TOPIC_NAME = "VirtualTopic.airag.practice.cache.invalidate";
    private static final String ACTIVEMQ_SCHEDULED_DELAY = "AMQ_SCHEDULED_DELAY";

    @Resource
    @Qualifier("practiceCacheTopicJmsTemplate")
    private JmsTemplate topicJmsTemplate;

    public void sendKnowledgeChanged(String knowledgeBaseId, long delayMillis) {
        CacheInvalidationMessage payload = new CacheInvalidationMessage(
                UUID.randomUUID().toString(),
                CacheInvalidationMessage.TYPE_KNOWLEDGE_CHANGED,
                knowledgeBaseId,
                System.currentTimeMillis()
        );

        try {
            topicJmsTemplate.convertAndSend(
                    VIRTUAL_TOPIC_NAME,
                    JSON.toJSONString(payload),
                    message -> {
                        if (delayMillis > 0L) {
                            message.setLongProperty(ACTIVEMQ_SCHEDULED_DELAY, delayMillis);
                        }
                        return message;
                    }
            );
            log.info("发送缓存失效事件: kbId={}, delayMs={}", knowledgeBaseId, delayMillis);
        } catch (Exception e) {
            // 缓存失效失败不能回滚知识库主业务；本地版本缓存短 TTL 会提供最终一致性兜底。
            log.error("发送缓存失效事件失败: kbId={}, delayMs={}",knowledgeBaseId, delayMillis, e);
        }
    }
}
