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
//update-begin---author:ys ---date:2026-07-09  for：MySQL-ES异步同步-----------
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
    public void sendSyncMessage(String action, String documentId, String knowledgeBaseId) {
        if (jmsTemplate == null) {
            log.warn("[MQ同步] JmsTemplate 未注入，跳过发送同步消息，请确保已引入 activemq 依赖");
            return;
        }

        try {
            EsSyncMessage message = new EsSyncMessage(action, documentId, knowledgeBaseId);
            String jsonStr = JSON.toJSONString(message);
            log.info("[MQ同步] 发送同步消息: queue={}, action={}, docId={}", QUEUE_NAME, action, documentId);
            jmsTemplate.convertAndSend(QUEUE_NAME, jsonStr);
        } catch (Exception e) {
            log.error("[MQ同步] 发送同步消息异常: docId={}", documentId, e);
        }
    }
}
//update-end---author:ys ---date:2026-07-09  for：MySQL-ES异步同步-----------
