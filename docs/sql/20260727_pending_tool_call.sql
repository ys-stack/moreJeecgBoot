CREATE TABLE IF NOT EXISTS `ai_pending_tool_call` (
  `id` varchar(36) NOT NULL COMMENT '确认单ID',
  `session_id` varchar(36) NOT NULL COMMENT '会话ID',
  `message_id` varchar(36) NOT NULL COMMENT '消息ID',
  `tool_id` varchar(36) NOT NULL COMMENT '工具定义ID',
  `tool_code` varchar(100) NOT NULL COMMENT '工具编码',
  `arguments_json` text NOT NULL COMMENT '服务端保存的精确参数',
  `arguments_hash` char(64) NOT NULL COMMENT '参数SHA-256',
  `user_id` varchar(36) NOT NULL COMMENT '发起用户ID',
  `status` varchar(20) NOT NULL COMMENT 'PENDING/EXECUTING/EXECUTED/CANCELLED/EXPIRED/FAILED',
  `expires_at` datetime NOT NULL COMMENT '过期时间',
  `executed_at` datetime DEFAULT NULL COMMENT '执行时间',
  `output_result` text DEFAULT NULL COMMENT '执行结果',
  `error_msg` varchar(1000) DEFAULT NULL COMMENT '错误信息',
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pending_owner` (`user_id`, `status`, `expires_at`),
  KEY `idx_pending_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI写工具待确认请求';

ALTER TABLE `ai_tool_call_log`
  ADD COLUMN `pending_call_id` varchar(36) DEFAULT NULL COMMENT '服务端确认单ID' AFTER `message_id`,
  ADD KEY `idx_pending_call_id` (`pending_call_id`);

