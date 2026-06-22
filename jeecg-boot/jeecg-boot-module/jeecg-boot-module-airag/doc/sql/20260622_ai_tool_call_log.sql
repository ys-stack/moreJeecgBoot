-- ============================================
-- AI工具调用日志表
-- 创建时间：2026-06-22
-- 说明：记录每次 Tool Calling 的执行过程，用于调试和审计
-- ============================================

CREATE TABLE IF NOT EXISTS `ai_tool_call_log` (
  `id`              varchar(36)    NOT NULL              COMMENT '主键ID',
  `session_id`      varchar(36)    DEFAULT NULL          COMMENT '关联的聊天会话ID',
  `message_id`      varchar(36)    DEFAULT NULL          COMMENT '关联的消息ID',
  `tool_code`       varchar(100)   NOT NULL              COMMENT '工具编码',
  `tool_name`       varchar(100)   DEFAULT NULL          COMMENT '工具名称（冗余，方便查询）',
  `input_params`    text           DEFAULT NULL          COMMENT '调用入参 JSON',
  `output_result`   text           DEFAULT NULL          COMMENT '执行结果 JSON',
  `status`          varchar(20)    NOT NULL              COMMENT '执行状态: success / error / timeout',
  `error_msg`       varchar(1000)  DEFAULT NULL          COMMENT '错误信息',
  `duration_ms`     int            DEFAULT NULL          COMMENT '执行耗时(毫秒)',
  `model_name`      varchar(100)   DEFAULT NULL          COMMENT '调用的模型名称',
  `create_by`       varchar(50)    DEFAULT NULL          COMMENT '创建人',
  `create_time`     datetime       DEFAULT NULL          COMMENT '创建日期',
  PRIMARY KEY (`id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_tool_code` (`tool_code`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI工具调用日志表';
