-- ============================================================
-- AI 评测体系表
-- 创建日期：2026-07-05
-- 说明：
-- 1. ai_eval_dataset：评测集，保存 RAG / Agent 的黄金用例
-- 2. ai_eval_result：评测结果，保存每次运行的逐用例评分
-- 3. 按 run_id 聚合 ai_eval_result 即可生成评测报告
-- ============================================================

CREATE TABLE IF NOT EXISTS `ai_eval_dataset` (
  `id` varchar(36) NOT NULL COMMENT '主键ID',
  `case_code` varchar(64) NOT NULL COMMENT '用例编码，如 RAG_001、AGENT_001',
  `case_name` varchar(128) NOT NULL COMMENT '用例名称',
  `eval_type` varchar(20) NOT NULL COMMENT '评测类型：rag / agent',
  `scenario` varchar(64) DEFAULT NULL COMMENT '业务场景：qa / refusal / order / user / ticket 等',
  `question` text NOT NULL COMMENT '用户输入问题',

  `knowledge_base_id` varchar(36) DEFAULT NULL COMMENT 'RAG 用例指定的知识库ID',
  `expected_answer` text DEFAULT NULL COMMENT '预期答案或答案要点',
  `expected_keywords` varchar(1000) DEFAULT NULL COMMENT '预期关键词JSON数组',
  `expected_references` text DEFAULT NULL COMMENT '预期引用JSON数组，可存 chunkId / docId / 文件名',
  `expected_reject` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否预期拒答：0否 1是',

  `expected_tool_name` varchar(100) DEFAULT NULL COMMENT 'Agent 预期调用工具编码',
  `expected_tool_params` text DEFAULT NULL COMMENT 'Agent 预期工具参数JSON',
  `expected_task_result` text DEFAULT NULL COMMENT 'Agent 预期任务结果或校验点',

  `difficulty` varchar(20) DEFAULT 'normal' COMMENT '难度：easy / normal / hard',
  `weight` decimal(6,2) NOT NULL DEFAULT 1.00 COMMENT '用例权重',
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',

  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `sys_org_code` varchar(64) DEFAULT NULL COMMENT '所属部门编码',
  `tenant_id` varchar(10) DEFAULT '0' COMMENT '租户ID',

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_case_code` (`case_code`),
  KEY `idx_eval_type` (`eval_type`),
  KEY `idx_scenario` (`scenario`),
  KEY `idx_status` (`status`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI评测集';

CREATE TABLE IF NOT EXISTS `ai_eval_result` (
  `id` varchar(36) NOT NULL COMMENT '主键ID',
  `run_id` varchar(36) NOT NULL COMMENT '评测运行ID，同一次评测共用',
  `run_name` varchar(128) DEFAULT NULL COMMENT '评测运行名称',
  `dataset_id` varchar(36) NOT NULL COMMENT '评测用例ID，关联 ai_eval_dataset.id',
  `case_code` varchar(64) NOT NULL COMMENT '用例编码快照',
  `eval_type` varchar(20) NOT NULL COMMENT '评测类型：rag / agent',

  `prompt_code` varchar(64) DEFAULT NULL COMMENT '本次评测使用的Prompt编码',
  `prompt_version` int DEFAULT NULL COMMENT '本次评测使用的Prompt版本',
  `model_provider` varchar(50) DEFAULT NULL COMMENT '模型供应商',
  `model_name` varchar(100) DEFAULT NULL COMMENT '模型名称',

  `question` text DEFAULT NULL COMMENT '用户输入问题快照',
  `actual_answer` text DEFAULT NULL COMMENT '模型实际回答',
  `actual_references` text DEFAULT NULL COMMENT 'RAG 实际引用JSON',
  `actual_tool_calls` text DEFAULT NULL COMMENT 'Agent 实际工具调用JSON',
  `raw_response` mediumtext DEFAULT NULL COMMENT '原始响应JSON',

  `answer_relevance_score` decimal(5,2) DEFAULT NULL COMMENT 'RAG回答相关性得分，0-100',
  `reference_hit_score` decimal(5,2) DEFAULT NULL COMMENT 'RAG引用命中得分，0-100',
  `reject_score` decimal(5,2) DEFAULT NULL COMMENT 'RAG拒答得分，0-100',
  `tool_selection_score` decimal(5,2) DEFAULT NULL COMMENT 'Agent工具选择得分，0-100',
  `param_accuracy_score` decimal(5,2) DEFAULT NULL COMMENT 'Agent参数准确得分，0-100',
  `task_completion_score` decimal(5,2) DEFAULT NULL COMMENT 'Agent任务完成得分，0-100',
  `total_score` decimal(5,2) DEFAULT NULL COMMENT '综合得分，0-100',
  `passed` tinyint(1) DEFAULT NULL COMMENT '是否通过：0否 1是',

  `duration_ms` bigint DEFAULT NULL COMMENT '单用例耗时，毫秒',
  `prompt_tokens` int DEFAULT NULL COMMENT '输入token数',
  `completion_tokens` int DEFAULT NULL COMMENT '输出token数',
  `total_tokens` int DEFAULT NULL COMMENT '总token数',
  `status` varchar(20) NOT NULL DEFAULT 'success' COMMENT '执行状态：success / fail / error / skipped',
  `error_msg` varchar(1000) DEFAULT NULL COMMENT '错误信息',
  `judge_detail` text DEFAULT NULL COMMENT '评分明细JSON',

  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `sys_org_code` varchar(64) DEFAULT NULL COMMENT '所属部门编码',
  `tenant_id` varchar(10) DEFAULT '0' COMMENT '租户ID',

  PRIMARY KEY (`id`),
  KEY `idx_run_id` (`run_id`),
  KEY `idx_dataset_id` (`dataset_id`),
  KEY `idx_case_code` (`case_code`),
  KEY `idx_eval_type` (`eval_type`),
  KEY `idx_prompt` (`prompt_code`, `prompt_version`),
  KEY `idx_model_name` (`model_name`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI评测结果';
