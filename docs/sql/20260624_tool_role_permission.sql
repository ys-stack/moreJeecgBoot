-- docs/sql/20260624_tool_role_permission.sql

CREATE TABLE IF NOT EXISTS `ai_tool_role_permission` (
                                                         `id`          varchar(36)   NOT NULL              COMMENT '主键',
    `tool_id`     varchar(36)   NOT NULL              COMMENT '关联 ai_tool_definition.id',
    `role_code`   varchar(50)   NOT NULL              COMMENT '角色编码，如 admin、hr、user',
    `create_by`   varchar(50)   DEFAULT NULL          COMMENT '创建人',
    `create_time` datetime      DEFAULT NULL          COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tool_role` (`tool_id`, `role_code`),
    KEY `idx_tool_id` (`tool_id`),
    KEY `idx_role_code` (`role_code`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具角色权限关联表';

-- 给三个工具分配权限
-- queryOrder: admin 和 user 都能调
INSERT INTO `ai_tool_role_permission` (`id`, `tool_id`, `role_code`, `create_by`, `create_time`)
VALUES
    ('perm_001', 'tool_001', 'admin', 'admin', NOW()),
    ('perm_002', 'tool_001', 'user',  'admin', NOW());

-- queryUser: admin 和 user 都能调
INSERT INTO `ai_tool_role_permission` (`id`, `tool_id`, `role_code`, `create_by`, `create_time`)
VALUES
    ('perm_003', 'tool_002', 'admin', 'admin', NOW()),
    ('perm_004', 'tool_002', 'user',  'admin', NOW());

-- createTicket: 只有 admin 能调
INSERT INTO `ai_tool_role_permission` (`id`, `tool_id`, `role_code`, `create_by`, `create_time`)
VALUES
    ('perm_005', 'tool_003', 'admin', 'admin', NOW());