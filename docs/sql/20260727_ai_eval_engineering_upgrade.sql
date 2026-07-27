-- AI评测工程化升级（适用于已经执行过20260705脚本的数据库）
-- MySQL 8.0.29+ 支持 ADD COLUMN IF NOT EXISTS；低版本请先用 DESC 检查后逐条执行。

ALTER TABLE `ai_eval_dataset`
  ADD COLUMN IF NOT EXISTS `expected_chunk_keywords` text DEFAULT NULL COMMENT '预期召回片段关键词JSON数组' AFTER `expected_references`,
  ADD COLUMN IF NOT EXISTS `should_require_confirm` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'Agent是否应要求二次确认' AFTER `expected_task_result`;

ALTER TABLE `ai_eval_result`
  ADD COLUMN IF NOT EXISTS `case_weight` decimal(6,2) NOT NULL DEFAULT 1.00 COMMENT '执行时用例权重快照' AFTER `question`,
  ADD COLUMN IF NOT EXISTS `chunk_hit_score` decimal(5,2) DEFAULT NULL COMMENT 'RAG召回片段关键词命中得分' AFTER `reference_hit_score`,
  ADD COLUMN IF NOT EXISTS `confirmation_score` decimal(5,2) DEFAULT NULL COMMENT 'Agent二次确认行为得分' AFTER `task_completion_score`;

CREATE TABLE IF NOT EXISTS `ai_eval_run` (
  `id` varchar(36) NOT NULL COMMENT '运行ID',
  `run_name` varchar(128) NOT NULL COMMENT '运行名称',
  `status` varchar(20) NOT NULL COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/INTERRUPTED',
  `eval_type` varchar(20) DEFAULT NULL,
  `prompt_code` varchar(64) DEFAULT NULL,
  `prompt_version` int DEFAULT NULL,
  `model_provider` varchar(50) DEFAULT NULL,
  `model_name` varchar(100) DEFAULT NULL,
  `request_json` text DEFAULT NULL,
  `case_snapshot` mediumtext DEFAULT NULL,
  `total_cases` int NOT NULL DEFAULT 0,
  `processed_cases` int NOT NULL DEFAULT 0,
  `passed_cases` int NOT NULL DEFAULT 0,
  `current_case_code` varchar(64) DEFAULT NULL,
  `error_msg` varchar(1000) DEFAULT NULL,
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `create_by` varchar(50) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL,
  `tenant_id` varchar(10) DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_eval_run_owner` (`create_by`, `create_time`),
  KEY `idx_eval_run_status` (`status`),
  KEY `idx_eval_run_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI评测运行';

-- 修正旧种子中与queryOrder真实JSON Schema不一致的参数名。
UPDATE `ai_eval_dataset`
SET `expected_tool_params` = REPLACE(`expected_tool_params`, '"orderNo"', '"orderCode"')
WHERE `expected_tool_name` = 'queryOrder';

-- 写工具只评估“是否发起确认”，评测期间绝不真正执行。
UPDATE `ai_eval_dataset`
SET `should_require_confirm` = 1
WHERE `expected_tool_name` = 'createTicket';
