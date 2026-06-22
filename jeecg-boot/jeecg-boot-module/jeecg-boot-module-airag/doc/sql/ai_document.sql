-- ============================================
-- AI文档表
-- 用途：管理知识库下的文档信息，一个文档解析后产生多个分片
-- 创建时间：2026-06-15
-- ============================================

CREATE TABLE IF NOT EXISTS `ai_document` (
  `id` varchar(36) NOT NULL COMMENT '主键ID',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建日期',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新日期',
  `sys_org_code` varchar(64) DEFAULT NULL COMMENT '所属部门',
  `tenant_id` varchar(32) DEFAULT NULL COMMENT '租户id',
  `knowledge_base_id` varchar(36) NOT NULL COMMENT '所属知识库ID',
  `title` varchar(200) NOT NULL COMMENT '文档标题',
  `doc_type` varchar(20) DEFAULT 'markdown' COMMENT '文档类型（markdown/pdf/txt/docx）',
  `file_name` varchar(255) DEFAULT NULL COMMENT '原始文件名',
  `file_path` varchar(500) DEFAULT NULL COMMENT '文件存储路径',
  `file_size` bigint DEFAULT 0 COMMENT '文件大小（字节）',
  `status` varchar(20) DEFAULT 'pending' COMMENT '状态（pending/parsing/completed/failed）',
  `chunk_count` int DEFAULT 0 COMMENT '分片数量（解析后更新）',
  `total_chars` int DEFAULT 0 COMMENT '文档总字符数',
  `error_msg` varchar(500) DEFAULT NULL COMMENT '解析失败时的错误信息',
  `metadata` text DEFAULT NULL COMMENT '扩展元数据JSON',
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_base_id` (`knowledge_base_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI文档';
