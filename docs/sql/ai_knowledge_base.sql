-- ============================================
-- AI知识库表
-- 用途：管理知识库的基本信息，一个知识库下包含多个文档
-- 创建时间：2026-06-15
-- ============================================

CREATE TABLE IF NOT EXISTS `ai_knowledge_base` (
  `id` varchar(36) NOT NULL COMMENT '主键ID',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建日期',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新日期',
  `sys_org_code` varchar(64) DEFAULT NULL COMMENT '所属部门',
  `tenant_id` varchar(32) DEFAULT NULL COMMENT '租户id',
  `name` varchar(100) NOT NULL COMMENT '知识库名称',
  `description` varchar(500) DEFAULT NULL COMMENT '知识库描述',
  `embed_model_id` varchar(36) DEFAULT NULL COMMENT '向量模型ID',
  `status` varchar(20) DEFAULT 'active' COMMENT '状态（active/inactive）',
  `role_code` varchar(255) DEFAULT NULL COMMENT '可见角色编码（逗号分隔，为空表示所有人可见）',
  `doc_count` int DEFAULT 0 COMMENT '文档数量（冗余计数）',
  `chunk_count` int DEFAULT 0 COMMENT '分片总数（冗余计数）',
  `metadata` text DEFAULT NULL COMMENT '扩展元数据JSON',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI知识库';

-- Day4: 新增 role_code 字段（已有表执行此 ALTER）
ALTER TABLE `ai_knowledge_base` ADD COLUMN IF NOT EXISTS `role_code` varchar(255) DEFAULT NULL COMMENT '可见角色编码（逗号分隔，为空表示所有人可见）' AFTER `status`;
