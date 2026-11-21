-- T5 hardening (slug hardening, 2026-11-15). SEC-11 / MON-16 / pass refund-reversal review.
-- 1. cy_signup_device (SEC-11): one row per device that already took the app sign-up points. Only a SHA-256 of the
--    device identifier (identifierForVendor or App Attest key id) is stored, never the raw value.
-- 2. Console pages (sections below): read and safe edit only; every permission is granted to NO role.
--    商品与会费 (MON-16, /funds-aftercare/commerce-console) and 通行证撤回退款复核 (/funds-aftercare/pass-review).
--    Audit rows go to the existing business_audit_event table; no new audit table.
-- No production switch is written. Idempotent: CREATE TABLE IF NOT EXISTS, menu rows guarded by NOT EXISTS(perms).
--
-- ROLLBACK:
--   DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE remark = 'T5 hardening');
--   DELETE FROM sys_menu WHERE remark = 'T5 hardening' AND menu_type = 'F';
--   DELETE FROM sys_menu WHERE remark = 'T5 hardening' AND menu_type = 'C';
--   DROP TABLE IF EXISTS cy_signup_device;        -- every device becomes "first account" again

CREATE TABLE IF NOT EXISTS cy_signup_device (
  device_hash CHAR(64)    NOT NULL COMMENT 'SHA-256 hex of the normalized device identifier',
  channel     VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'phone | apple | wechat: the sign-up that took the points',
  member_id   BIGINT      NULL COMMENT 'the member that took the sign-up points',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (device_hash),
  KEY idx_signup_device_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SEC-11 app sign-up points: one grant per device';

-- ---------------------------------------------------------------- 商品与会费(/funds-aftercare/commerce-console)
SET @t5_dir_unique := (SELECT COUNT(*) FROM sys_menu WHERE path = 'funds-aftercare' AND parent_id = 0 AND menu_type = 'M');
SET @t5_dir := (SELECT menu_id FROM sys_menu WHERE path = 'funds-aftercare' AND parent_id = 0 AND menu_type = 'M' AND @t5_dir_unique = 1 LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '商品与会费', @t5_dir, 60, 'commerce-console', 'business/commerceconsole/index', 1, 0, 'C', '0', '0', 'business:commerceConsole:list', 'shopping', 'admin', NOW(), 'T5 hardening'
FROM dual
WHERE @t5_dir IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:commerceConsole:list');
SET @t5_page := (SELECT menu_id FROM sys_menu WHERE perms = 'business:commerceConsole:list' AND menu_type = 'C' ORDER BY menu_id LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '停售商品与停用会费方案', @t5_page, 1, '', '', 1, 0, 'F', '0', '0', 'business:commerceConsole:edit', '#', 'admin', NOW(), 'T5 hardening'
FROM dual
WHERE @t5_page IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:commerceConsole:edit');

-- ---------------------------------------------------------------- 通行证撤回退款复核(/funds-aftercare/pass-review)
SET @t5_dir_unique := (SELECT COUNT(*) FROM sys_menu WHERE path = 'funds-aftercare' AND parent_id = 0 AND menu_type = 'M');
SET @t5_dir := (SELECT menu_id FROM sys_menu WHERE path = 'funds-aftercare' AND parent_id = 0 AND menu_type = 'M' AND @t5_dir_unique = 1 LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '通行证撤回退款复核', @t5_dir, 61, 'pass-review', 'business/passreview/index', 1, 0, 'C', '0', '0', 'business:passReview:list', 'checkbox', 'admin', NOW(), 'T5 hardening'
FROM dual
WHERE @t5_dir IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:passReview:list');
SET @t5_page := (SELECT menu_id FROM sys_menu WHERE perms = 'business:passReview:list' AND menu_type = 'C' ORDER BY menu_id LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '重新开通或关闭复核', @t5_page, 1, '', '', 1, 0, 'F', '0', '0', 'business:passReview:resolve', '#', 'admin', NOW(), 'T5 hardening'
FROM dual
WHERE @t5_page IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:passReview:resolve');

-- 回读:应为 4(2 个页面 + 2 个按钮);少于该数说明「资金与售后」目录缺失或不唯一。
-- runner: assert=scalar-true
SELECT COUNT(*) = 4 FROM sys_menu WHERE perms IN ('business:commerceConsole:list', 'business:commerceConsole:edit', 'business:passReview:list', 'business:passReview:resolve') AND status = '0';
