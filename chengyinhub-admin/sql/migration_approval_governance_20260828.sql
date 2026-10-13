-- NF-01/NF-15/NF-17 审批治理：反向记录关联、审计 requestId 真非空、严格查询权限。
-- 仅生成迁移文件；禁止在本任务执行，合并前由总控走 chengyin-db-migrate。

ALTER TABLE audit_record
  ADD COLUMN reverted_record_id BIGINT NULL COMMENT 'REVERT 指向被撤销的原审批记录 id' AFTER snapshot_json,
  ADD KEY idx_audit_record_reverted (reverted_record_id);

-- 旧表 request_id 可 NULL，MySQL UNIQUE 允许多个 NULL，原唯一键因此不是完整幂等闸。
-- 用不可变主键生成确定性兼容键，再收紧为 NOT NULL；禁止随机 UUID 回填。
UPDATE business_audit_event
SET request_id = CONCAT('legacy:', LPAD(id, 20, '0'))
WHERE request_id IS NULL OR request_id = '';

-- 迁移按仓库铁律先于新 jar 执行；旧 jar 的优惠券核销写者仍会显式插入 NULL。
-- BEFORE INSERT 用业务字段生成稳定键，保证迁移到部署窗口不打断旧写者，也不退回随机 UUID。
DROP TRIGGER IF EXISTS bi_business_audit_event_request_id;
DELIMITER $$
CREATE TRIGGER bi_business_audit_event_request_id
BEFORE INSERT ON business_audit_event
FOR EACH ROW
SET NEW.request_id = CASE
  WHEN NEW.request_id IS NULL OR NEW.request_id = '' THEN SHA2(CONCAT_WS(':',
    NEW.domain_type, NEW.domain_id, NEW.action, NEW.target_type,
    COALESCE(NEW.target_id, 0), COALESCE(NEW.relation_id, 0)), 256)
  ELSE NEW.request_id
END$$
DELIMITER ;

ALTER TABLE business_audit_event
    ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
      COMMENT '主体命名空间：UNKNOWN/MEMBER/SYS_USER；不猜历史身份' AFTER owner_member_id,
    MODIFY COLUMN request_id VARCHAR(64) NOT NULL COMMENT '稳定业务请求键；禁止 NULL/空串';

SET @audit_menu := (
  SELECT menu_id FROM sys_menu
  WHERE perms = 'business:audit:list' AND menu_type = 'C'
  ORDER BY menu_id LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '查看操作审计', @audit_menu, 20, '', '', '', '', 1, 0,
       'F', '0', '0', 'business:audit:log:list', '#', 'admin', NOW(), '', NULL,
       'NF-15 平台管理员审计日志只读权限'
WHERE @audit_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:audit:log:list');

SET @audit_log_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:audit:log:list' ORDER BY menu_id LIMIT 1
);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, @audit_log_menu WHERE @audit_log_menu IS NOT NULL;

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
  AND EXISTS (
    SELECT 1 FROM information_schema.triggers
    WHERE trigger_schema = DATABASE()
      AND trigger_name = 'bi_business_audit_event_request_id'
      AND event_object_table = 'business_audit_event'
      AND action_timing = 'BEFORE' AND event_manipulation = 'INSERT')
  AND @audit_menu IS NOT NULL
  AND @audit_log_menu IS NOT NULL
  AND EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = @audit_log_menu)
  AS assertion_ok;
