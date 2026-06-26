-- ============================================
-- AI练习模块 菜单权限SQL
-- 创建时间：2026-06-16
-- 说明：注册「AI 练习」父菜单及其子菜单，并给 admin 角色授权
-- ============================================

-- 1. 注册父菜单「AI 练习」
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000001', NULL, 'AI 练习', '/practice-ai', NULL, 1, NULL, '/practice-ai/assistant', 0, NULL, '1', 9.0, 0, 'ant-design:robot-outlined', 0, 0, 0, 0, 'AI 练习模块', 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);

-- 2. 注册子菜单「需求分析助手」
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000002', '9000000000000000001', '需求分析助手', '/practice-ai/assistant', 'practice/aiassistant/index', 1, NULL, NULL, 1, NULL, '1', 1.0, 0, 'ant-design:bulb-outlined', 1, 0, 0, 0, NULL, 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);

-- 3. 注册子菜单「知识库管理」
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000004', '9000000000000000001', '知识库管理', '/practice-ai/knowledge-base', 'practice/knowledgebase/index', 1, NULL, NULL, 1, NULL, '1', 2.0, 0, 'ant-design:database-outlined', 1, 0, 0, 0, NULL, 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);

-- 4. 注册子菜单「文档管理」
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000003', '9000000000000000001', '文档管理', '/practice-ai/doc-manager', 'practice/docmanager/index', 1, NULL, NULL, 1, NULL, '1', 3.0, 0, 'ant-design:folder-open-outlined', 1, 0, 0, 0, NULL, 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);

-- 5. 注册子菜单「批量解析」
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000005', '9000000000000000001', '批量解析', '/practice-ai/batch-parse', 'practice/batchparse/index', 1, NULL, NULL, 1, NULL, '1', 4.0, 0, 'ant-design:thunderbolt-outlined', 1, 0, 0, 0, NULL, 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);

-- 6. 注册子菜单「Tool Calling」
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000006', '9000000000000000001', 'Tool Calling', '/practice-ai/tool-calling', 'practice/toolcalling/index', 1, NULL, NULL, 1, NULL, '1', 6.0, 0, 'ant-design:api-outlined', 1, 0, 0, 0, 'Tool Calling 测试页', 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);

-- 7. 注册子菜单「调用日志」
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000007', '9000000000000000001', '调用日志', '/practice-ai/call-logs', 'practice/calllogs/index', 1, NULL, NULL, 1, NULL, '1', 7.0, 0, 'ant-design:file-text-outlined', 1, 0, 0, 0, '工具调用日志查看', 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);


INSERT INTO `jeecg-boot`.`sys_permission` (`id`, `parent_id`, `name`, `url`, `component`, `is_route`, `component_name`, `redirect`, `menu_type`, `perms`, `perms_type`, `sort_no`, `always_show`, `icon`, `is_leaf`, `keep_alive`, `hidden`, `hide_tab`, `description`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`, `rule_flag`, `status`, `internal_or_external`) VALUES ('9000000000000000008', '9000000000000000001', '工具管理', '/practice-ai/tool-manage', 'practice/toolmanage/index', 1, NULL, NULL, 1, NULL, '1', 7.00, 0, 'ant-design:tool-outlined', 1, 0, 0, 0, 'Tool Calling 工具定义与调用日志', 'admin', '2026-06-23 21:49:38', 'admin', '2026-06-23 21:49:38', 0, 0, '1', 0);

-- 8. 注册子菜单「线程池监控」
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000009', '9000000000000000001', '线程池监控', '/practice-ai/threadpool-monitor', 'practice/threadpoolmonitor/index', 1, NULL, NULL, 1, NULL, '1', 8.0, 0, 'ant-design:dashboard-outlined', 1, 0, 0, 0, '线程池运行状态监控', 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);

-- 9. 注册子菜单「Tool Calling 对话」
INSERT INTO sys_permission (id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('9000000000000000010', '9000000000000000001', 'Tool Calling 对话', '/practice-ai/tool-chat', 'practice/toolchat/index', 1, NULL, NULL, 1, NULL, '1', 5.0, 0, 'ant-design:tool-outlined', 1, 0, 0, 0, 'Tool Calling 对话，支持写操作二次确认', 'admin', NOW(), 'admin', NOW(), 0, 0, '1', 0);

-- 10. 给 admin 角色授权（角色 ID 替换成你实际查出来的）
INSERT INTO sys_role_permission (id, role_id, permission_id)
VALUES
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000001'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000002'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000003'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000004'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000005'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000006'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000007'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000008'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000009'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000010');
