package org.jeecg.modules.airag.practice.cache.mq;

import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.cache.service.IKnowledgeCacheVersionService;
import org.jeecg.modules.airag.practice.cache.service.IRagAnswerCacheService;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/** 每个应用实例独立消费 Virtual Topic 对应的 Queue，清理本节点 Caffeine。 */
@Slf4j
@Component
public class CacheInvalidationConsumer {

    @Resource
    private IKnowledgeCacheVersionService versionService;

    @Resource
    private IRagAnswerCacheService answerCacheService;

    @JmsListener(
            destination = "${practice.cache.mq.consumer-queue:Consumer.local.VirtualTopic.airag.practice.cache.invalidate}",
            concurrency = "1"
    )
    public void consume(String json) {
        CacheInvalidationMessage message;
        try {
            message = JSON.parseObject(json, CacheInvalidationMessage.class);
        } catch (Exception e) {
            // 非法消息直接丢弃，避免反复重投形成毒消息。
            log.warn("缓存失效消息 JSON 解析失败: error={}", e.getMessage());
            return;
        }
        if (message == null
                || message.getEventType() == null
                || message.getKnowledgeBaseId() == null
                || message.getKnowledgeBaseId().isBlank()) {
            log.warn("收到非法缓存失效消息");
            return;
        }
        if (!CacheInvalidationMessage.TYPE_KNOWLEDGE_CHANGED.equals(message.getEventType())) {
            log.warn("收到未知缓存失效事件: eventType={}", message.getEventType());
            return;
        }

        versionService.evictLocalAndRedis(message.getKnowledgeBaseId());
        answerCacheService.evictLocalByKnowledgeBaseId(message.getKnowledgeBaseId());
        log.info("完成本节点缓存失效: eventId={}, kbId={}",
                message.getEventId(), message.getKnowledgeBaseId());
    }
}
