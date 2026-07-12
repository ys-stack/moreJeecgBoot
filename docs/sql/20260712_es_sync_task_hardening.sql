-- 已部署 es_sync_task 表的增量升级脚本。
-- 新增索引服务于 Outbox 分页扫描、超时重投和最大重试次数过滤。

ALTER TABLE `es_sync_task`
  DROP INDEX `idx_status_action`,
  ADD INDEX `idx_status_retry_update` (`status`, `retry_count`, `update_time`);
