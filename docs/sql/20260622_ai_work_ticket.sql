-- ============================================
-- 工单表 + 工具定义数据 + 样例工单
-- 创建时间：2026-06-22
-- 说明：createTicket 工具的目标表，同时插入 3 条工具定义和样例工单数据
-- ============================================

-- ==================== 1. 创建工单表 ====================
CREATE TABLE IF NOT EXISTS `ai_work_ticket` (
  `id`              varchar(36)    NOT NULL              COMMENT '主键ID',
  `ticket_no`       varchar(50)    NOT NULL              COMMENT '工单编号（自动生成，如 TK202606220001）',
  `title`           varchar(200)   NOT NULL              COMMENT '工单标题',
  `description`     text           DEFAULT NULL          COMMENT '工单描述',
  `ticket_type`     varchar(50)    DEFAULT 'bug'         COMMENT '工单类型: bug / feature / task / question',
  `priority`        varchar(20)    DEFAULT 'medium'      COMMENT '优先级: low / medium / high / urgent',
  `status`          varchar(20)    DEFAULT 'open'        COMMENT '状态: open / in_progress / resolved / closed',
  `assignee`        varchar(50)    DEFAULT NULL          COMMENT '处理人',
  `requester`       varchar(50)    DEFAULT NULL          COMMENT '提交人',
  `resolution`      text           DEFAULT NULL          COMMENT '处理结果',
  `create_by`       varchar(50)    DEFAULT NULL          COMMENT '创建人',
  `create_time`     datetime       DEFAULT NULL          COMMENT '创建日期',
  `update_by`       varchar(50)    DEFAULT NULL          COMMENT '更新人',
  `update_time`     datetime       DEFAULT NULL          COMMENT '更新日期',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`),
  KEY `idx_status` (`status`),
  KEY `idx_assignee` (`assignee`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI工单表';

-- ==================== 2. 插入 3 条工具定义 ====================

-- 2.1 查询订单（queryOrder）—— 查 JeecgBoot 自带的 jeecg_order_main 表
INSERT INTO ai_tool_definition
(id, tool_code, tool_name, description, parameters_schema, endpoint_type, handler_ref, category, status, is_read_only, timeout_ms, require_confirm, sort_order, create_by, create_time)
VALUES (
  '9000000000000000010',
  'queryOrder',
  '查询订单',
  '根据订单号查询订单信息。支持精确匹配订单号（order_code），返回订单金额、类型、日期、备注等。当用户询问订单相关问题时使用此工具。',
  '{
    "type": "object",
    "properties": {
      "orderCode": {
        "type": "string",
        "description": "订单号，例如 B100、NC911、A100"
      }
    },
    "required": ["orderCode"]
  }',
  'JAVA_BEAN',
  'orderToolHandler',
  'query',
  'active',
  1,
  5000,
  0,
  1,
  'admin', NOW()
);

-- 2.2 查询用户（queryUser）—— 查 JeecgBoot 自带的 sys_user 表
INSERT INTO ai_tool_definition
(id, tool_code, tool_name, description, parameters_schema, endpoint_type, handler_ref, category, status, is_read_only, timeout_ms, require_confirm, sort_order, create_by, create_time)
VALUES (
  '9000000000000000011',
  'queryUser',
  '查询用户',
  '根据用户名、姓名、工号或手机号查询系统用户信息。返回用户的真实姓名、邮箱、手机、部门、状态等。当用户询问某个人的信息时使用此工具。',
  '{
    "type": "object",
    "properties": {
      "keyword": {
        "type": "string",
        "description": "查询关键词，可以是用户名(username)、真实姓名(realname)、工号(work_no)或手机号(phone)"
      }
    },
    "required": ["keyword"]
  }',
  'JAVA_BEAN',
  'userToolHandler',
  'query',
  'active',
  1,
  5000,
  0,
  2,
  'admin', NOW()
);

-- 2.3 创建工单（createTicket）—— 写 ai_work_ticket 表，需用户确认
INSERT INTO ai_tool_definition
(id, tool_code, tool_name, description, parameters_schema, endpoint_type, handler_ref, category, status, is_read_only, timeout_ms, require_confirm, sort_order, create_by, create_time)
VALUES (
  '9000000000000000012',
  'createTicket',
  '创建工单',
  '创建一个新的工单。需要用户提供标题和描述，可选指定类型（bug/feature/task/question）、优先级（low/medium/high/urgent）和处理人。这是写操作，执行前需要用户确认。',
  '{
    "type": "object",
    "properties": {
      "title": {
        "type": "string",
        "description": "工单标题，简明描述问题"
      },
      "description": {
        "type": "string",
        "description": "工单详细描述"
      },
      "ticketType": {
        "type": "string",
        "enum": ["bug", "feature", "task", "question"],
        "description": "工单类型，默认 bug"
      },
      "priority": {
        "type": "string",
        "enum": ["low", "medium", "high", "urgent"],
        "description": "优先级，默认 medium"
      },
      "assignee": {
        "type": "string",
        "description": "处理人用户名"
      }
    },
    "required": ["title", "description"]
  }',
  'JAVA_BEAN',
  'ticketToolHandler',
  'write',
  'active',
  0,
  10000,
  1,
  3,
  'admin', NOW()
);

-- ==================== 3. 插入样例工单数据 ====================
INSERT INTO ai_work_ticket (id, ticket_no, title, description, ticket_type, priority, status, assignee, requester, create_by, create_time) VALUES
('9000000000000000020', 'TK202606200001', '登录页面偶现白屏',
 '部分用户在 Chrome 125 版本下登录时页面白屏，清除缓存后可恢复。怀疑是前端缓存策略问题。',
 'bug', 'high', 'open', 'admin', 'zhangsan', 'zhangsan', '2026-06-20 09:30:00'),

('9000000000000000021', 'TK202606200002', '新增报表导出功能',
 '需要在数据分析模块增加 Excel 和 PDF 格式的报表导出功能，支持自定义列选择和筛选条件导出。',
 'feature', 'medium', 'in_progress', 'admin', 'jeecg', 'jeecg', '2026-06-20 14:15:00'),

('9000000000000000022', 'TK202606210001', '数据同步异常排查',
 '生产环境数据同步任务在凌晨 2 点偶发失败，日志显示连接超时，需排查 ES 集群连接池配置。',
 'bug', 'urgent', 'open', 'admin', 'admin', 'admin', '2026-06-21 08:45:00');
