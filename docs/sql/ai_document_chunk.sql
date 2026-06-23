-- ============================================
-- AI文档分片表
-- 用途：存储Markdown文档解析后的分片数据
-- 创建时间：2026-06-15
-- ============================================

CREATE TABLE IF NOT EXISTS `ai_document_chunk` (
  `id` varchar(36) NOT NULL COMMENT '主键ID',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建日期',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新日期',
  `sys_org_code` varchar(64) DEFAULT NULL COMMENT '所属部门',
  `tenant_id` varchar(32) DEFAULT NULL COMMENT '租户id',
  `document_id` varchar(36) NOT NULL COMMENT '文档ID（同一次上传的唯一标识）',
  `chunk_index` int NOT NULL DEFAULT 0 COMMENT '分片序号（从0开始）',
  `heading` varchar(500) DEFAULT NULL COMMENT '所属标题路径，如：概述 > 背景',
  `content` text NOT NULL COMMENT '分片内容',
  `token_count` int DEFAULT 0 COMMENT '预估Token数',
  `char_count` int DEFAULT 0 COMMENT '字符数',
  `chunk_type` varchar(20) DEFAULT 'text' COMMENT '分片类型（heading/text/table/code）',
  `metadata` text DEFAULT NULL COMMENT '扩展元数据JSON',
  `source_file_name` varchar(255) DEFAULT NULL COMMENT '源文件名',
  `source_file_path` varchar(500) DEFAULT NULL COMMENT '源文件存储路径',
  PRIMARY KEY (`id`),
  KEY `idx_document_id` (`document_id`),
  KEY `idx_heading` (`heading`(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI文档分片表';


CREATE TABLE `airag_knowledge`  (
                                    `id` varchar(36) NOT NULL,
                                    `create_by` varchar(50) NULL DEFAULT NULL COMMENT '创建人',
                                    `create_time` datetime NULL DEFAULT NULL COMMENT '创建日期',
                                    `update_by` varchar(50) NULL DEFAULT NULL COMMENT '更新人',
                                    `update_time` datetime NULL DEFAULT NULL COMMENT '更新日期',
                                    `sys_org_code` varchar(64) NULL DEFAULT NULL COMMENT '所属部门',
                                    `tenant_id` varchar(32) NULL DEFAULT NULL COMMENT '租户id',
                                    `name` varchar(100) NULL DEFAULT NULL COMMENT '知识库名称',
                                    `descr` varchar(500) NULL DEFAULT NULL COMMENT '描述',
                                    `embed_id` varchar(32) NULL DEFAULT NULL COMMENT '向量模型id',
                                    `status` varchar(32) NULL DEFAULT NULL COMMENT '状态',
                                    PRIMARY KEY (`id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `airag_knowledge_doc`  (
                                        `id` varchar(36) NOT NULL,
                                        `create_by` varchar(50) NULL DEFAULT NULL COMMENT '创建人',
                                        `create_time` datetime NULL DEFAULT NULL COMMENT '创建日期',
                                        `update_by` varchar(50) NULL DEFAULT NULL COMMENT '更新人',
                                        `update_time` datetime NULL DEFAULT NULL COMMENT '更新日期',
                                        `sys_org_code` varchar(64) NULL DEFAULT NULL COMMENT '所属部门',
                                        `tenant_id` varchar(32) NULL DEFAULT NULL COMMENT '租户id',
                                        `knowledge_id` varchar(32) NULL DEFAULT NULL COMMENT '知识库id',
                                        `title` varchar(100) NULL DEFAULT NULL COMMENT '标题',
                                        `type` varchar(32) NULL DEFAULT NULL COMMENT '类型',
                                        `content` text NULL COMMENT '内容',
                                        `status` varchar(32) NULL DEFAULT NULL COMMENT '状态',
                                        `metadata` text NULL COMMENT '元数据',
                                        PRIMARY KEY (`id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;