-- 第4周 Day2：知识库、文档和向量接口功能权限。
-- menu_type=2 表示按钮/接口权限；使用 INSERT IGNORE 便于重复执行。

INSERT IGNORE INTO sys_permission
(id, parent_id, name, menu_type, perms, perms_type, sort_no, is_leaf, hidden,
 create_by, create_time, update_by, update_time, del_flag, status, internal_or_external)
VALUES
('9000000000000000101', '9000000000000000004', '知识库查询', 2, 'practice:kb:list', '1', 1, 1, 1, 'admin', NOW(), 'admin', NOW(), 0, '1', 0),
('9000000000000000102', '9000000000000000004', '知识库新增', 2, 'practice:kb:add', '1', 2, 1, 1, 'admin', NOW(), 'admin', NOW(), 0, '1', 0),
('9000000000000000103', '9000000000000000004', '知识库编辑', 2, 'practice:kb:edit', '1', 3, 1, 1, 'admin', NOW(), 'admin', NOW(), 0, '1', 0),
('9000000000000000104', '9000000000000000004', '知识库删除', 2, 'practice:kb:delete', '1', 4, 1, 1, 'admin', NOW(), 'admin', NOW(), 0, '1', 0),
('9000000000000000105', '9000000000000000003', '文档查询', 2, 'practice:doc:list', '1', 1, 1, 1, 'admin', NOW(), 'admin', NOW(), 0, '1', 0),
('9000000000000000106', '9000000000000000003', '文档上传', 2, 'practice:doc:upload', '1', 2, 1, 1, 'admin', NOW(), 'admin', NOW(), 0, '1', 0),
('9000000000000000107', '9000000000000000003', '文档删除', 2, 'practice:doc:delete', '1', 3, 1, 1, 'admin', NOW(), 'admin', NOW(), 0, '1', 0),
('9000000000000000108', '9000000000000000003', '文档向量化', 2, 'practice:doc:vectorize', '1', 4, 1, 1, 'admin', NOW(), 'admin', NOW(), 0, '1', 0),
('9000000000000000109', '9000000000000000003', '向量检索', 2, 'practice:vector:search', '1', 5, 1, 1, 'admin', NOW(), 'admin', NOW(), 0, '1', 0);

INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
SELECT REPLACE(UUID(), '-', ''), role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.id IN (
  '9000000000000000101', '9000000000000000102', '9000000000000000103',
  '9000000000000000104', '9000000000000000105', '9000000000000000106',
  '9000000000000000107', '9000000000000000108', '9000000000000000109')
WHERE role.role_code = 'admin';

