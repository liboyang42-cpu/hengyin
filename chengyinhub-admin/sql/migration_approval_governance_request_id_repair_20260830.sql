-- 修复 migration_approval_governance_20260828.sql 的部分落库状态。
-- 历史迁移已添加 actor_type、reverted_record_id、菜单和授权，但 request_id 仍可空。
-- 生产账号在启用 binary log 时无权 CREATE TRIGGER；稳定兼容键由 MyBatis 插入语句生成。

UPDATE business_audit_event
SET request_id = CONCAT('legacy:', LPAD(id, 20, '0'))
WHERE request_id IS NULL OR request_id = '';

DROP TRIGGER IF EXISTS bi_business_audit_event_request_id;

ALTER TABLE business_audit_event
  MODIFY COLUMN request_id VARCHAR(64) NOT NULL COMMENT '稳定业务请求键；禁止 NULL/空串';

SET @audit_menu := (
  SELECT menu_id FROM sys_menu
  WHERE perms = 'business:audit:list' AND menu_type = 'C'
  ORDER BY menu_id LIMIT 1
);
SET @audit_log_menu := (
  SELECT menu_id FROM sys_menu
  WHERE perms = 'business:audit:log:list'
  ORDER BY menu_id LIMIT 1
);

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'audit_record'
     AND column_name = 'reverted_record_id' AND data_type = 'bigint') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'business_audit_event'
         AND column_name = 'request_id' AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'business_audit_event'
         AND column_name = 'actor_type' AND is_nullable = 'NO'
         AND column_default = 'UNKNOWN') = 1
  AND (SELECT COUNT(*) FROM business_audit_event
       WHERE request_id IS NULL OR request_id = '') = 0
  AND EXISTS (
    SELECT 1 FROM (
      SELECT index_name, MIN(non_unique) AS non_unique,
             GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
      FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'business_audit_event'
      GROUP BY index_name
    ) indexes_exact
    WHERE index_name = 'uk_business_audit_request' AND non_unique = 0
      AND cols = 'domain_type,domain_id,action,request_id')
  AND NOT EXISTS (
    SELECT 1 FROM information_schema.triggers
    WHERE trigger_schema = DATABASE()
      AND trigger_name = 'bi_business_audit_event_request_id'
      AND event_object_table = 'business_audit_event'
      AND action_timing = 'BEFORE' AND event_manipulation = 'INSERT')
  AND @audit_menu IS NOT NULL
  AND @audit_log_menu IS NOT NULL
  AND EXISTS (
    SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = @audit_log_menu)
  AS assertion_ok;
