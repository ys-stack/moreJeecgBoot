package org.jeecg.modules.airag.practice.sync.consumer;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.doc.entity.AiDocument;
import org.jeecg.modules.airag.practice.doc.entity.AiDocumentChunk;
import org.jeecg.modules.airag.practice.doc.mapper.AiDocumentMapper;
import org.jeecg.modules.airag.practice.doc.service.IAiDocumentChunkService;
import org.jeecg.modules.airag.practice.sync.dto.EsSyncMessage;
import org.jeecg.modules.airag.practice.sync.producer.EsSyncProducer;
import org.jeecg.modules.airag.practice.vector.service.VectorStoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * ES 异步同步消息消费者 (ActiveMQ)
 * 具备 @Retryable 指数退避重试与失败降级 (@Recover) 逻辑
 *
 * @Author: jeecg-boot
 * @Date: 2026-07-09
 */
//update-begin---author:ys ---date:2026-07-09  for：MySQL-ES异步同步-----------
@Slf4j
@Component
public class EsSyncConsumer {

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private IAiDocumentChunkService aiDocumentChunkService;

    @Autowired
    private AiDocumentMapper aiDocumentMapper;

    /**
     * 消费同步消息并写入 ES。
     * 当同步操作由于 ES 故障或网络问题抛出异常时，触发 @Retryable 机制：
     * - maxAttempts = 5: 最大尝试 5 次
     * - backoff: 初始延迟 2s，后续以 2 倍因子指数递增 (2s, 4s, 8s, 16s)
     *
     * @param jsonStr MQ 中的 JSON 消息字符串
     */
    @JmsListener(destination = EsSyncProducer.QUEUE_NAME)
    @Retryable(
            retryFor = { Exception.class },
            maxAttempts = 5,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public void consumeSyncMessage(String jsonStr) {
        log.info("[MQ同步] 接收到 ES 异步同步消息: {}", jsonStr);
        EsSyncMessage message = JSON.parseObject(jsonStr, EsSyncMessage.class);
        if (message == null || message.getAction() == null || message.getDocumentId() == null) {
            log.warn("[MQ同步] 消息对象解析不完整，拒绝处理");
            return;
        }

        String action = message.getAction();
        String documentId = message.getDocumentId();

        if (EsSyncMessage.ACTION_INDEX.equalsIgnoreCase(action)) {
            handleIndexAction(documentId, message.getKnowledgeBaseId());
        } else if (EsSyncMessage.ACTION_DELETE.equalsIgnoreCase(action)) {
            handleDeleteAction(documentId);
        } else {
            log.warn("[MQ同步] 未知操作类型: {}", action);
        }
    }

    /**
     * 处理索引同步逻辑 (同步 MySQL 数据到 ES)
     */
    private void handleIndexAction(String documentId, String knowledgeBaseId) {
        log.info("[MQ同步] 开始同步文档到 ES: docId={}", documentId);

        // 1. 查询文档详情
        AiDocument doc = aiDocumentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("[MQ同步] 数据库中找不到文档记录，同步终止: docId={}", documentId);
            return;
        }

        // 2. 从 MySQL 读分片数据
        List<AiDocumentChunk> chunks = aiDocumentChunkService.listByDocumentId(documentId);
        if (chunks.isEmpty()) {
            log.warn("[MQ同步] 文档无有效分片，跳过同步: docId={}", documentId);
            return;
        }

        // 3. 调用向量服务写入 ES
        int count = vectorStoreService.vectorizeAndStore(documentId, knowledgeBaseId, chunks);
        log.info("[MQ同步] 成功同步 {} 条向量到 ES: docId={}", count, documentId);

        // 4. 更新 MySQL 状态为已向量化
        doc.setStatus("vectorized");
        doc.setUpdateTime(new Date());
        aiDocumentMapper.updateById(doc);
    }

    /**
     * 处理删除同步逻辑 (同步清理 ES 中的数据)
     */
    private void handleDeleteAction(String documentId) {
        log.info("[MQ同步] 开始清理 ES 中的文档数据: docId={}", documentId);
        long count = vectorStoreService.deleteByDocumentId(documentId);
        log.info("[MQ同步] 成功清理 ES 向量数={}条: docId={}", count, documentId);
    }

    /**
     * 最终重试失败时的兜底降级方案。
     * 当重试 5 次依然抛出异常时，@Recover 会拦截并执行以下兜底操作，
     * 避免消息被退回 MQ 产生死循环拥堵队列。
     *
     * @param e       拦截到的异常
     * @param jsonStr 原始接收到的消息
     */
    @Recover
    public void recoverSyncFailure(Exception e, String jsonStr) {
        log.error("[MQ同步] 同步重试 5 次依然失败！开始执行降级兜底方案。原消息: {}, 异常信息: {}", jsonStr, e.getMessage());
        try {
            EsSyncMessage message = JSON.parseObject(jsonStr, EsSyncMessage.class);
            if (message != null && EsSyncMessage.ACTION_INDEX.equalsIgnoreCase(message.getAction())) {
                // 标记文档状态为同步失败，记录错误日志，等待定时对账恢复
                AiDocument doc = aiDocumentMapper.selectById(message.getDocumentId());
                if (doc != null) {
                    doc.setStatus("vectorize_failed");
                    doc.setErrorMsg("MQ同步重试5次失败: " + e.getMessage());
                    doc.setUpdateTime(new Date());
                    aiDocumentMapper.updateById(doc);
                    log.info("[MQ同步] 已将文档状态重置为: vectorize_failed, docId={}", message.getDocumentId());
                }
            }
        } catch (Exception ex) {
            log.error("[MQ同步] 执行 Recover 兜底逻辑异常", ex);
        }
    }
}
//update-end---author:ys ---date:2026-07-09  for：MySQL-ES异步同步-----------
