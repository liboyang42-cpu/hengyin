-- T5 hardening (slug hardening, 2026-11-15). SEC-11 / MON-16 / pass refund-reversal review.
-- 1. cy_signup_device (SEC-11): one row per device that already took the app sign-up points. Only a SHA-256 of the
--    device identifier (identifierForVendor or App Attest key id) is stored, never the raw value.
-- 2. Console pages (sections below): read and safe edit only; every permission is granted to NO role.
-- No production switch is written. Idempotent: CREATE TABLE IF NOT EXISTS, menu rows guarded by NOT EXISTS(perms).
--
-- ROLLBACK:
--   DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE remark = 'T5 hardening');
--   DELETE FROM sys_menu WHERE remark = 'T5 hardening' AND menu_type = 'F';
--   DELETE FROM sys_menu WHERE remark = 'T5 hardening' AND menu_type = 'C';
--   DROP TABLE IF EXISTS cy_pass_review_action;   -- archive first if operators acted on reviews
--   DROP TABLE IF EXISTS cy_signup_device;        -- every device becomes "first account" again

CREATE TABLE IF NOT EXISTS cy_signup_device (
  device_hash CHAR(64)    NOT NULL COMMENT 'SHA-256 hex of the normalized device identifier',
  channel     VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'phone | apple | wechat: the sign-up that took the points',
  member_id   BIGINT      NULL COMMENT 'the member that took the sign-up points',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (device_hash),
  KEY idx_signup_device_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SEC-11 app sign-up points: one grant per device';
