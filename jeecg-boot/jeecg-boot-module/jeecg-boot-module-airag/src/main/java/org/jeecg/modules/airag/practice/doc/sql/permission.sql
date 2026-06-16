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

-- 5. 给 admin 角色授权（角色 ID 替换成你实际查出来的）
INSERT INTO sys_role_permission (id, role_id, permission_id)
VALUES
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000001'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000002'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000003'),
    (REPLACE(UUID(), '-', ''), 'f6817f48af4fb3af11b9e8bf182f618b', '9000000000000000004');
