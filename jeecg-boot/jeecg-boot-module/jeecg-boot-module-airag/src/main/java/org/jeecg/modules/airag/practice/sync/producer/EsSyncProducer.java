package org.jeecg.modules.airag.practice.sync.producer;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.sync.dto.EsSyncMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * ES 异步同步消息生产者 (ActiveMQ)
 *
 * @Author: jeecg-boot
 * @Date: 2026-07-09
 */
@Slf4j
@Component
public class EsSyncProducer {

    /**
     * 队列名称
     */
    public static final String QUEUE_NAME = "airag.practice.es.sync.queue";

    @Autowired(required = false)
    private JmsTemplate jmsTemplate;

    /**
     * 发送同步消息到 ActiveMQ 队列
     *
     * @param action          操作类型: INDEX/DELETE
     * @param documentId      文档ID
     * @param knowledgeBaseId 知识库ID
     */
    public void sendSyncMessage(String taskId, String action, String documentId, String knowledgeBaseId) {
        if (jmsTemplate == null) {
            throw new IllegalStateException("JmsTemplate 未注入，无法发送 ES 同步消息");
        }

        EsSyncMessage message = new EsSyncMessage(taskId, action, documentId, knowledgeBaseId);
        String jsonStr = JSON.toJSONString(message);
        log.info("[MQ同步] 发送同步消息: queue={}, taskId={}, action={}, docId={}",
                QUEUE_NAME, taskId, action, documentId);
        // 发送失败必须向上抛出，让 Outbox 保持可重试状态。
        jmsTemplate.convertAndSend(QUEUE_NAME, jsonStr);
    }
}
