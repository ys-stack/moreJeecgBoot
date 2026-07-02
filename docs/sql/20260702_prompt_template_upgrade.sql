-- ============================================================
-- Prompt 模板生产级升级
-- 1. 新增 change_log 字段（变更说明/审计日志）
-- 2. 新增 (prompt_code, version) 唯一索引（防重复版本）
-- ============================================================

-- 1. 新增变更说明字段
ALTER TABLE ai_prompt_template
    ADD COLUMN change_log VARCHAR(500) DEFAULT NULL COMMENT '变更说明（记录本次修改的原因和内容）'
    AFTER description;

-- 2. 唯一索引：同一个 prompt_code 下 version 不能重复
ALTER TABLE ai_prompt_template
    ADD UNIQUE INDEX uk_code_version (prompt_code, version);

-- 3. 给现有种子数据补上 change_log
UPDATE ai_prompt_template SET change_log = '初始版本' WHERE change_log IS NULL;
