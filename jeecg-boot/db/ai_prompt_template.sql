-- ============================================================
-- AI Prompt 模板表（Day3 Prompt 工程实践）
-- ============================================================
CREATE TABLE IF NOT EXISTS `ai_prompt_template` (
  `id` varchar(36) NOT NULL COMMENT '主键ID',
  `prompt_code` varchar(64) NOT NULL COMMENT '模板编码（唯一标识，如 structured_analysis）',
  `version` int NOT NULL DEFAULT 1 COMMENT '版本号（同一 prompt_code 可以有多个版本）',
  `template` text NOT NULL COMMENT 'Prompt 模板内容，用 {变量名} 标记可替换变量',
  `variables` varchar(512) DEFAULT NULL COMMENT '模板变量列表（JSON 数组，如 ["userQuestion","orderInfo"]）',
  `description` varchar(256) DEFAULT NULL COMMENT '模板用途说明',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：0=禁用 1=启用',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `sys_org_code` varchar(64) DEFAULT NULL COMMENT '所属部门编码',
  `tenant_id` varchar(10) DEFAULT '0' COMMENT '租户ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_version` (`prompt_code`, `version`) COMMENT '同一编码下版本号唯一'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Prompt 模板表';

-- 预置几条模板数据，方便上手测试
-- 注意：模板作为 SystemMessage 发送，用户消息单独作为 UserMessage，不需要在模板里重复 {userQuestion}
INSERT INTO `ai_prompt_template` (`id`, `prompt_code`, `version`, `template`, `variables`, `description`, `status`, `create_by`, `create_time`)
VALUES
('1', 'structured_analysis', 1,
 '你是一个需求分析助手。用户会输入一段需求描述，请你按以下 JSON 格式输出分析结果：\n\n```json\n{\n  "background": "需求背景分析",\n  "goal": "核心目标",\n  "apis": [{"method": "GET/POST", "path": "/api/xxx", "description": "接口说明"}],\n  "tables": [{"tableName": "表名", "description": "说明", "keyFields": ["字段1"]}],\n  "risks": ["风险点1"]\n}\n```\n\n注意：\n1. 必须严格输出 JSON，不要有多余的文字\n2. APIs 和 tables 至少各一个\n3. risks 至少列出一个风险点',
 '[]', '需求结构化分析（从 Day1 硬编码迁移过来）', 1, 'admin', NOW()),

('2', 'chat_default', 1,
 '你是一个智能助手，请用简洁清晰的语言回答用户的问题。',
 '[]', '默认聊天模板', 1, 'admin', NOW()),

('3', 'order_query', 1,
 '你是一个订单查询助手。根据以下订单信息，回答用户的问题。\n\n订单信息：\n{orderInfo}',
 '["orderInfo"]', '订单查询场景模板（orderInfo 由后端查询后注入）', 1, 'admin', NOW());
