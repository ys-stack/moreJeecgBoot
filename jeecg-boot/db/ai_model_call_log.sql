-- =============================================
-- Day 4：模型调用日志表
-- 所属模块：jeecg-module-ai
-- 用途：自动记录每次 AI 模型调用的详细信息，
--       支持按天/按用户/按模型的调用统计和成本核算
-- =============================================

DROP TABLE IF EXISTS `ai_model_call_log`;
CREATE TABLE `ai_model_call_log` (
  `id`                  varchar(36)   NOT NULL                        COMMENT '主键ID（雪花ID）',

  -- 请求标识
  `request_id`          varchar(64)   NULL DEFAULT NULL               COMMENT '请求追踪ID（UUID，串联一次完整对话链路）',
  `biz_type`            varchar(50)   NULL DEFAULT NULL               COMMENT '业务类型（chat=对话, structured=结构化输出, stream=流式, embedding=向量化, rag=RAG问答）',

  -- 模型信息
  `model_provider`      varchar(50)   NULL DEFAULT NULL               COMMENT '模型供应商（deepseek, qwen, zhipu, siliconflow, openai等）',
  `model_name`          varchar(100)  NULL DEFAULT NULL               COMMENT '模型名称（deepseek-chat, qwen-plus, glm-4-flash等）',
  `model_version`       varchar(50)   NULL DEFAULT NULL               COMMENT '模型版本',

  -- Token 统计
  `prompt_tokens`       int(11)       NULL DEFAULT 0                  COMMENT '输入 token 数',
  `completion_tokens`   int(11)       NULL DEFAULT 0                  COMMENT '输出 token 数',
  `total_tokens`        int(11)       NULL DEFAULT 0                  COMMENT '总 token 数',

  -- 调用详情
  `request_body`        text          NULL                            COMMENT '请求内容摘要（截取前500字，避免撑爆存储）',
  `response_body`       text          NULL                            COMMENT '响应内容摘要（截取前500字）',
  `prompt_code`         varchar(100)  NULL DEFAULT NULL               COMMENT '使用的 Prompt 模板编码',
  `prompt_version`      int(11)       NULL DEFAULT NULL               COMMENT '使用的 Prompt 模板版本号',

  -- 性能指标
  `duration_ms`         bigint(20)    NULL DEFAULT 0                  COMMENT '调用耗时（毫秒）',
  `first_token_ms`      bigint(20)    NULL DEFAULT NULL               COMMENT '首 token 耗时（毫秒，流式场景用）',

  -- 调用结果
  `status`              varchar(20)   NULL DEFAULT 'success'          COMMENT '调用状态（success=成功, fail=失败, timeout=超时, rate_limit=被限流）',
  `error_msg`           varchar(500)  NULL DEFAULT NULL               COMMENT '错误信息（失败时记录）',
  `retry_count`         int(11)       NULL DEFAULT 0                  COMMENT '重试次数',

  -- 成本估算
  `cost_estimate`       decimal(10,6) NULL DEFAULT 0.000000           COMMENT '本次调用预估费用（元），根据模型单价 × token 数计算',

  -- 用户与租户
  `user_id`             varchar(36)   NULL DEFAULT NULL               COMMENT '调用用户ID',
  `user_name`           varchar(50)   NULL DEFAULT NULL               COMMENT '调用用户名',
  `tenant_id`           varchar(32)   NULL DEFAULT NULL               COMMENT '租户ID',

  -- 扩展
  `client_ip`           varchar(50)   NULL DEFAULT NULL               COMMENT '客户端IP',
  `api_path`            varchar(200)  NULL DEFAULT NULL               COMMENT '请求接口路径（如 /ai/chat, /ai/chat/structured）',
  `extra_data`          text          NULL                            COMMENT '扩展数据（JSON，存放模型返回的 usage 原始信息等）',

  -- JeecgBoot 标准审计字段
  `create_by`           varchar(50)   NULL DEFAULT NULL               COMMENT '创建人',
  `create_time`         datetime      NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `sys_org_code`        varchar(64)   NULL DEFAULT NULL               COMMENT '所属部门编码',

  PRIMARY KEY (`id`) USING BTREE,

  -- 按用户查调用记录
  INDEX `idx_user_id` (`user_id`) USING BTREE,
  -- 按时间范围统计（成本报表、Dashboard 高频使用）
  INDEX `idx_create_time` (`create_time`) USING BTREE,
  -- 按模型聚合统计
  INDEX `idx_model_name` (`model_name`) USING BTREE,
  -- 请求链路追踪
  INDEX `idx_request_id` (`request_id`) USING BTREE,
  -- 按业务类型筛选
  INDEX `idx_biz_type` (`biz_type`) USING BTREE,
  -- 按状态过滤（快速查失败记录）
  INDEX `idx_status` (`status`) USING BTREE

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  ROW_FORMAT = DYNAMIC
  COMMENT = 'AI 模型调用日志表';
