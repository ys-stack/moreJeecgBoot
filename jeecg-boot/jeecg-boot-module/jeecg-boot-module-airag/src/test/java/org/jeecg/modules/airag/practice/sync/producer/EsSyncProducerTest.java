package org.jeecg.modules.airag.practice.sync.producer;

import org.junit.jupiter.api.Test;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EsSyncProducerTest {

    @Test
    void messageContainsOutboxTaskId() {
        JmsTemplate jmsTemplate = mock(JmsTemplate.class);
        EsSyncProducer producer = new EsSyncProducer();
        ReflectionTestUtils.setField(producer, "jmsTemplate", jmsTemplate);

        producer.sendSyncMessage("task-1", "INDEX", "doc-1", "kb-1");

        verify(jmsTemplate).convertAndSend(eq(EsSyncProducer.QUEUE_NAME), contains("\"taskId\":\"task-1\""));
    }

    @Test
    void brokerFailureIsPropagatedToOutboxPublisher() {
        JmsTemplate jmsTemplate = mock(JmsTemplate.class);
        doThrow(new IllegalStateException("broker down"))
                .when(jmsTemplate).convertAndSend(eq(EsSyncProducer.QUEUE_NAME), contains("task-1"));
        EsSyncProducer producer = new EsSyncProducer();
        ReflectionTestUtils.setField(producer, "jmsTemplate", jmsTemplate);

        assertThrows(IllegalStateException.class,
                () -> producer.sendSyncMessage("task-1", "INDEX", "doc-1", "kb-1"));
    }
}
