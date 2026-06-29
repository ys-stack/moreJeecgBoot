-- 1. ai_chat_message 加字段：存工具调用详情
ALTER TABLE ai_chat_message ADD COLUMN tool_calls text DEFAULT NULL COMMENT '工具调用详情JSON';

-- 2. ai_chat_session 加字段：存会话摘要
ALTER TABLE ai_chat_session ADD COLUMN summary text DEFAULT NULL COMMENT '会话摘要';

-- 3. 新建 case 表
CREATE TABLE ai_tool_chat_case (
                                   id              varchar(36)    NOT NULL,
                                   case_name       varchar(200)   NOT NULL     COMMENT '用例名称',
                                   session_id      varchar(36)    NOT NULL     COMMENT '关联会话ID',
                                   user_id         varchar(50)    DEFAULT NULL COMMENT '所属用户',
                                   scenario        varchar(100)   DEFAULT NULL COMMENT '场景: order_query/user_lookup/ticket_create/multi_step',
                                   description     text           DEFAULT NULL COMMENT '用例描述',
                                   expected_tools  varchar(500)   DEFAULT NULL COMMENT '预期调用的工具(逗号分隔)',
                                   actual_tools    varchar(500)   DEFAULT NULL COMMENT '实际调用的工具(逗号分隔)',
                                   is_pass         tinyint(1)     DEFAULT NULL COMMENT '是否符合预期',
                                   create_by       varchar(50)    DEFAULT NULL,
                                   create_time     datetime       DEFAULT NULL,
                                   PRIMARY KEY (id),
                                   KEY idx_session_id (session_id),
                                   KEY idx_scenario (scenario)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tool Calling 对话用例';