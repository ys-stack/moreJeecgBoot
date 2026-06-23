-- ============================================
-- 2026-06-22 增量SQL：补字段 + 建表 + 补菜单
-- ============================================
-- 1. ai_knowledge_base 补 role_code（如果建表时没有这列）
-- 2. 新建 ai_model_call_log 表（模型调用日志）
-- 3. 新建 ai_prompt_template 表（Prompt模板）
-- 4. 补菜单：统计报表、RAG对话
-- ============================================


-- ============================================
-- 1. ai_knowledge_base 补字段
-- ============================================

ALTER TABLE `ai_knowledge_base` ADD COLUMN IF NOT EXISTS `role_code` varchar(255) DEFAULT NULL COMMENT '可见角色编码（逗号分隔，为空表示所有人可见）' AFTER `status`;


-- ============================================
-- 2. ai_model_call_log 建表
-- ============================================

CREATE TABLE IF NOT EXISTS `ai_model_call_log` (
  `id`                varchar(36)    NOT NULL                COMMENT '主键ID',
  `request_id`        varchar(64)    DEFAULT NULL            COMMENT '请求追踪ID',
  `biz_type`          varchar(30)    DEFAULT NULL            COMMENT '业务类型（chat/structured/stream/embedding/rag）',
  `model_provider`    varchar(50)    DEFAULT NULL            COMMENT '模型供应商',
  `model_name`        varchar(100)   DEFAULT NULL            COMMENT '模型名称',
  `model_version`     varchar(50)    DEFAULT NULL            COMMENT '模型版本',
  `prompt_tokens`     int            DEFAULT 0               COMMENT '输入token数',
  `completion_tokens` int            DEFAULT 0               COMMENT '输出token数',
  `total_tokens`      int            DEFAULT 0               COMMENT '总token数',
  `request_body`      text           DEFAULT NULL            COMMENT '请求内容摘要',
  `response_body`     text           DEFAULT NULL            COMMENT '响应内容摘要',
  `prompt_code`       varchar(50)    DEFAULT NULL            COMMENT 'Prompt模板编码',
  `prompt_version`    int            DEFAULT NULL            COMMENT 'Prompt模板版本号',
  `duration_ms`       bigint         DEFAULT 0               COMMENT '调用耗时（毫秒）',
  `first_token_ms`    bigint         DEFAULT 0               COMMENT '首token耗时（毫秒）',
  `status`            varchar(20)    DEFAULT 'success'       COMMENT '调用状态（success/fail/timeout/rate_limit）',
  `error_msg`         text           DEFAULT NULL            COMMENT '错误信息',
  `retry_count`       int            DEFAULT 0               COMMENT '重试次数',
  `cost_estimate`     decimal(10,6)  DEFAULT 0.000000        COMMENT '预估费用（元）',
  `user_id`           varchar(36)    DEFAULT NULL            COMMENT '调用用户ID',
  `user_name`         varchar(50)    DEFAULT NULL            COMMENT '调用用户名',
  `tenant_id`         varchar(32)    DEFAULT NULL            COMMENT '租户ID',
  `client_ip`         varchar(50)    DEFAULT NULL            COMMENT '客户端IP',
  `api_path`          varchar(200)   DEFAULT NULL            COMMENT '请求接口路径',
  `extra_data`        text           DEFAULT NULL            COMMENT '扩展数据（JSON）',
  `create_by`         varchar(50)    DEFAULT NULL            COMMENT '创建人',
  `create_time`       datetime       DEFAULT NULL            COMMENT '创建时间',
  `sys_org_code`      varchar(64)    DEFAULT NULL            COMMENT '所属部门编码',
  PRIMARY KEY (`id`),
  KEY `idx_request_id` (`request_id`),
  KEY `idx_biz_type` (`biz_type`),
  KEY `idx_status` (`status`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_model_name` (`model_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI模型调用日志';


-- ============================================
-- 3. ai_prompt_template 建表
-- ============================================

CREATE TABLE IF NOT EXISTS `ai_prompt_template` (
  `id`           varchar(36)  NOT NULL                COMMENT '主键ID',
  `prompt_code`  varchar(50)  NOT NULL                COMMENT '模板编码（唯一标识，如 structured_analysis）',
  `version`      int          NOT NULL DEFAULT 1      COMMENT '版本号',
  `template`     text         NOT NULL                COMMENT 'Prompt模板内容',
  `variables`    text         DEFAULT NULL            COMMENT '模板变量列表（JSON数组）',
  `description`  varchar(500) DEFAULT NULL            COMMENT '模板用途说明',
  `status`       int          DEFAULT 1               COMMENT '状态：0=禁用 1=启用',
  `create_by`    varchar(50)  DEFAULT NULL            COMMENT '创建人',
  `create_time`  datetime     DEFAULT NULL            COMMENT '创建时间',
  `update_by`    varchar(50)  DEFAULT NULL            COMMENT '更新人',
  `update_time`  datetime     DEFAULT NULL            COMMENT '更新时间',
  `sys_org_code` varchar(64)  DEFAULT NULL            COMMENT '所属部门编码',
  `tenant_id`    varchar(32)  DEFAULT NULL            COMMENT '租户ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prompt_code_version` (`prompt_code`, `version`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI Prompt模板';


-- ============================================
-- 4. 补菜单（挂在「AI练习」父菜单下）
--    父菜单 ID: 9000000000000000001
--    已有子菜单: 0002(需求分析), 0003(文档管理), 0004(知识库), 0005(批量解析)
-- ============================================

-- 4.1 统计报表（调用日志）
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000006', '9000000000000000001', '统计报表', '/practice-ai/call-log', 'practice/calllog/index', 1, NULL, NULL, 1, NULL, '1', 5.0, 0, 'ant-design:bar-chart-outlined', 1, 0, 0, 0, 'AI模型调用日志统计', 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);

-- 4.2 RAG 对话
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000007', '9000000000000000001', 'RAG 对话', '/practice-ai/rag-chat', 'practice/ragchat/index', 1, NULL, NULL, 1, NULL, '1', 6.0, 0, 'ant-design:message-outlined', 1, 0, 0, 0, 'RAG向量检索对话', 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);

-- 4.3 工具管理（工具定义 + 调用日志）
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000008', '9000000000000000001', '工具管理', '/practice-ai/tool-manage', 'practice/toolmanage/index', 1, NULL, NULL, 1, NULL, '1', 7.0, 0, 'ant-design:tool-outlined', 1, 0, 0, 0, 'Tool Calling 工具定义与调用日志', 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);

-- 4.4 给 admin 角色授权（role_id 替换成你实际的）
INSERT INTO sys_role_permission (id, role_id, permission_id)
VALUES
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000006'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000007'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000008');
