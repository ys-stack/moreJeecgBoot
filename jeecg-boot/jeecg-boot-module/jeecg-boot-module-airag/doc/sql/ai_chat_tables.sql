-- ================================================================
-- Day2: AI 对话会话表 & 对话消息表
-- 用于 RAG 聊天功能：会话管理 + 消息存储
-- ================================================================

-- 1. 会话表：记录一次对话会话
CREATE TABLE IF NOT EXISTS `ai_chat_session` (
  `id`                varchar(36)  NOT NULL                COMMENT '主键ID',
  `create_by`         varchar(50)  DEFAULT NULL            COMMENT '创建人',
  `create_time`       datetime     DEFAULT NULL            COMMENT '创建日期',
  `update_by`         varchar(50)  DEFAULT NULL            COMMENT '更新人',
  `update_time`       datetime     DEFAULT NULL            COMMENT '更新日期',
  `sys_org_code`      varchar(64)  DEFAULT NULL            COMMENT '所属部门',
  `tenant_id`         varchar(32)  DEFAULT NULL            COMMENT '租户id',
  `title`             varchar(200) DEFAULT NULL            COMMENT '会话标题（自动取首条用户消息摘要）',
  `user_id`           varchar(36)  NOT NULL                COMMENT '所属用户ID',
  `knowledge_base_id` varchar(36)  DEFAULT NULL            COMMENT '关联知识库ID（可选）',
  `model_provider`    varchar(50)  DEFAULT NULL            COMMENT '模型供应商',
  `model_name`        varchar(100) DEFAULT NULL            COMMENT '使用的模型名称',
  `status`            varchar(20)  DEFAULT 'active'        COMMENT '状态：active-活跃 / archived-已归档',
  `message_count`     int          DEFAULT 0               COMMENT '消息数量',
  `metadata`          text         DEFAULT NULL            COMMENT '扩展元数据(JSON)',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_knowledge_base_id` (`knowledge_base_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI对话会话表';


-- 2. 消息表：记录会话中的每条消息（用户提问 + AI 回答）
CREATE TABLE IF NOT EXISTS `ai_chat_message` (
  `id`                  varchar(36)    NOT NULL                COMMENT '主键ID',
  `create_by`           varchar(50)    DEFAULT NULL            COMMENT '创建人',
  `create_time`         datetime       DEFAULT NULL            COMMENT '创建日期',
  `session_id`          varchar(36)    NOT NULL                COMMENT '所属会话ID',
  `parent_message_id`   varchar(36)    DEFAULT NULL            COMMENT '父消息ID（用于回复链）',
  `role`                varchar(20)    NOT NULL                COMMENT '角色：user-用户 / assistant-AI / system-系统',
  `content`             text           NOT NULL                COMMENT '消息内容',
  `prompt_tokens`       int            DEFAULT 0               COMMENT 'Prompt token数',
  `completion_tokens`   int            DEFAULT 0               COMMENT 'Completion token数',
  `total_tokens`        int            DEFAULT 0               COMMENT '总 token数',
  `rag_context`         text           DEFAULT NULL            COMMENT 'RAG 检索到的上下文(JSON)',
  `rag_chunk_count`     int            DEFAULT 0               COMMENT 'RAG 检索到的分片数量',
  `model_provider`      varchar(50)    DEFAULT NULL            COMMENT '实际使用的模型供应商',
  `model_name`          varchar(100)   DEFAULT NULL            COMMENT '实际使用的模型名称',
  `duration_ms`         bigint         DEFAULT 0               COMMENT '模型响应耗时(毫秒)',
  `status`              varchar(20)    DEFAULT 'success'       COMMENT '状态：success-成功 / error-失败',
  `error_msg`           varchar(500)   DEFAULT NULL            COMMENT '错误信息',
  PRIMARY KEY (`id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_role` (`role`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI对话消息表';
