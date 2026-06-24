-- ============================================
-- AI工具定义 - 种子数据
-- 创建时间：2026-06-24
-- 说明：插入 queryOrder / queryUser / createTicket 三个工具的初始定义
-- ============================================

INSERT INTO `ai_tool_definition` (`id`, `tool_code`, `tool_name`, `description`, `parameters_schema`, `endpoint_type`, `handler_ref`, `category`, `status`, `is_read_only`, `timeout_ms`, `require_confirm`, `sort_order`, `create_by`, `create_time`)
VALUES
('tool_001', 'queryOrder', '查询订单', '根据订单编号查询订单信息，包括订单状态、金额、类型等。当用户询问某个订单的状态或详情时调用此工具。',
 '{"type":"object","properties":{"orderCode":{"type":"string","description":"订单编号，例如 B100"}},"required":["orderCode"]}',
 'JAVA_BEAN', 'orderToolHandler', 'query', 'active', 1, 5000, 0, 1, 'admin', NOW()),

('tool_002', 'queryUser', '查询用户', '根据关键词查询用户信息，支持按用户名、真实姓名、工号、手机号模糊搜索。当用户询问某个人的信息时调用此工具。',
 '{"type":"object","properties":{"keyword":{"type":"string","description":"查询关键词，可以是用户名、姓名、工号或手机号"}},"required":["keyword"]}',
 'JAVA_BEAN', 'userToolHandler', 'query', 'active', 1, 5000, 0, 2, 'admin', NOW()),

('tool_003', 'createTicket', '创建工单', '创建一个新的工作工单。这是一个写操作，会向数据库写入新记录。当用户要求创建工单、提交问题或报告Bug时调用此工具。',
 '{"type":"object","properties":{"title":{"type":"string","description":"工单标题，简要描述问题"},"description":{"type":"string","description":"工单详细描述，包括问题现象和复现步骤"},"ticketType":{"type":"string","description":"工单类型","enum":["bug","feature","task","incident"]},"priority":{"type":"string","description":"优先级","enum":["low","medium","high","urgent"]},"assignee":{"type":"string","description":"指派人用户名，可选"}},"required":["title","description"]}',
 'JAVA_BEAN', 'ticketToolHandler', 'write', 'active', 0, 10000, 1, 3, 'admin', NOW());
