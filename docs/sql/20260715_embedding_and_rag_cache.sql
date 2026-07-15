-- ============================================================================
-- AI Practice：Embedding 向量缓存 + RAG 答案缓存版本控制
-- 日期：2026-07-15
--
-- 实施前说明：
-- 1. 先备份 ai_knowledge_base 表。
-- 2. 本脚本只创建 MySQL 持久化向量缓存；RAG 最终答案存放在 Caffeine + Redis。
--    Redis 中的答案使用 AES-GCM 加密，密钥由 practice.cache.hmac-secret 隔离派生。
-- 3. 生产环境必须配置 practice.cache.hmac-secret，长度不少于 32 个字符。
-- 4. 每个应用实例必须使用不同的 ActiveMQ Virtual Topic 消费队列，例如：
--    Consumer.airag-node-01.VirtualTopic.airag.practice.cache.invalidate
--    Consumer.airag-node-02.VirtualTopic.airag.practice.cache.invalidate
-- 5. ActiveMQ Broker 需要开启 schedulerSupport=true，延迟双删消息才能生效。
-- ============================================================================

-- 知识库版本号会进入 RAG 答案缓存键。
-- 文档、分片、知识库权限或模型配置发生变化后，代码会将该值原子递增。
ALTER TABLE ai_knowledge_base
    ADD COLUMN cache_version BIGINT NOT NULL DEFAULT 1
        COMMENT '知识库缓存版本，内容、权限或模型变化后递增'
        AFTER chunk_count;

-- Embedding 持久化缓存。
-- Redis 丢失或应用重启后，可从该表回填向量，避免再次调用收费 API。
CREATE TABLE ai_embedding_cache
(
    id                    VARCHAR(32)  NOT NULL COMMENT 'MyBatis-Plus 主键',
    cache_key             CHAR(64)     NOT NULL COMMENT 'HMAC-SHA256 向量缓存唯一键',
    tenant_id             VARCHAR(32)  NOT NULL DEFAULT '0' COMMENT '租户 ID，防止跨租户复用私有向量',
    model_name            VARCHAR(128) NOT NULL COMMENT 'Embedding 模型名称',
    model_version         VARCHAR(64)  NOT NULL COMMENT 'Embedding 模型业务版本',
    normalization_version VARCHAR(64)  NOT NULL COMMENT '文本归一化算法版本',
    dimensions            INT          NOT NULL COMMENT '向量维度',
    vector_data           MEDIUMBLOB   NOT NULL COMMENT 'float[] 大端序二进制数据',
    vector_checksum       CHAR(64)     NOT NULL COMMENT 'vector_data 的 SHA-256 校验和',
    create_time           DATETIME     NOT NULL COMMENT '创建时间',
    update_time           DATETIME     NOT NULL COMMENT '更新时间',
    last_hit_time         DATETIME              COMMENT '最近一次 MySQL 回源命中时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_embedding_cache_key (cache_key),
    KEY idx_ai_embedding_tenant_model
        (tenant_id, model_name, model_version),
    KEY idx_ai_embedding_last_hit (last_hit_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'AI Embedding 向量持久化缓存';

-- 可选维护语句：建议由低峰期定时任务分批执行，不要一次删除大量数据。
-- DELETE FROM ai_embedding_cache
-- WHERE last_hit_time < DATE_SUB(NOW(), INTERVAL 90 DAY)
-- LIMIT 1000;

-- 验证结构。
SHOW COLUMNS FROM ai_knowledge_base LIKE 'cache_version';
SHOW CREATE TABLE ai_embedding_cache;

-- 回滚参考：确认不再有应用读写新结构后再执行。
-- DROP TABLE ai_embedding_cache;
-- ALTER TABLE ai_knowledge_base DROP COLUMN cache_version;
