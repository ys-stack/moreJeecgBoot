package org.jeecg.modules.airag.practice.cache.mq;

import jakarta.jms.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.core.JmsTemplate;

/** ActiveMQ Virtual Topic 生产者配置。 */
@Configuration
public class PracticeCacheMqConfig {

    /**
     * 默认 Queue 模板，供现有 ES 同步等点对点消息使用。
     */
    @Bean("jmsTemplate")
    @Primary
    public JmsTemplate practiceQueueJmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setPubSubDomain(false);
        template.setDeliveryPersistent(true);
        template.setExplicitQosEnabled(true);
        return template;
    }

    @Bean("practiceCacheTopicJmsTemplate")
    public JmsTemplate practiceCacheTopicJmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setPubSubDomain(true);
        template.setDeliveryPersistent(true);
        template.setExplicitQosEnabled(true);
        return template;
    }
}
