-- ES数据同步任务日志表 (Transactional Outbox 模式)
-- 每次发起 ES 同步（INDEX 或 DELETE）时，与本地主业务事务强绑定插入此表。
-- 保证如果事务提交成功，同步意图必定落库持久化，用于应对 MQ 挂机、网络抖动等情况。

CREATE TABLE `es_sync_task` (
  `id` varchar(36) NOT NULL COMMENT '主键ID',
  `action` varchar(10) NOT NULL COMMENT '操作类型(INDEX/DELETE)',
  `document_id` varchar(36) NOT NULL COMMENT '文档ID',
  `knowledge_base_id` varchar(36) DEFAULT NULL COMMENT '知识库ID',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态(PENDING/SUCCESS/FAILED)',
  `retry_count` int(11) NOT NULL DEFAULT '0' COMMENT '重试次数',
  `error_msg` text COMMENT '错误日志',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_action` (`status`,`action`),
  KEY `idx_document_id` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ES数据同步任务日志表';
