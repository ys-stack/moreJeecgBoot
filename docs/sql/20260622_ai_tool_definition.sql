-- ============================================
-- AI工具定义表
-- 创建时间：2026-06-22
-- 说明：存储 Tool Calling 的工具元数据，供模型 Function Calling 使用
-- ============================================

CREATE TABLE IF NOT EXISTS `ai_tool_definition` (
  `id`              varchar(36)    NOT NULL              COMMENT '主键ID',
  `tool_code`       varchar(100)   NOT NULL              COMMENT '工具编码（唯一标识，发送给模型）',
  `tool_name`       varchar(100)   NOT NULL              COMMENT '工具名称（中文显示名）',
  `description`     varchar(1000)  DEFAULT NULL          COMMENT '工具描述（发送给模型，帮助模型决定何时调用）',
  `parameters_schema` text         DEFAULT NULL          COMMENT '参数 JSON Schema（OpenAI function calling 格式）',
  `endpoint_type`   varchar(20)    DEFAULT 'JAVA_BEAN'   COMMENT '端点类型: JAVA_BEAN / REST_API / SQL_QUERY',
  `handler_ref`     varchar(200)   DEFAULT NULL          COMMENT '处理器引用（Bean名 / URL / SQL模板ID）',
  `category`        varchar(50)    DEFAULT NULL          COMMENT '工具分类: query / write / notify / system',
  `status`          varchar(20)    DEFAULT 'active'      COMMENT '状态: active / inactive',
  `is_read_only`    tinyint(1)     DEFAULT 1             COMMENT '是否只读操作（0=否,1=是）',
  `timeout_ms`      int            DEFAULT 5000          COMMENT '执行超时时间(毫秒)',
  `require_confirm` tinyint(1)     DEFAULT 0             COMMENT '写操作是否需要用户确认（0=否,1=是）',
  `sort_order`      int            DEFAULT 0             COMMENT '排序号',
  `create_by`       varchar(50)    DEFAULT NULL          COMMENT '创建人',
  `create_time`     datetime       DEFAULT NULL          COMMENT '创建日期',
  `update_by`       varchar(50)    DEFAULT NULL          COMMENT '更新人',
  `update_time`     datetime       DEFAULT NULL          COMMENT '更新日期',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_code` (`tool_code`),
  KEY `idx_category` (`category`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI工具定义表';
