-- ================= migration_20261130_money_switch_two_person.sql =================
-- W7 x-money-on-readiness (MERCHANT-15): opening a money area needs two people.
-- One admin asks (cy_money_switch_request, with a reason); a DIFFERENT admin approves (with a reason), and only then
-- the area is switched on and the usual cy_money_switch_audit row is written. A request expires after 24 hours.
-- Switching an area OFF stays one step. The new button business:moneyswitch:approve is granted to NO role.
-- No production switch is written: this migration inserts no cy_money_switch row.
-- Idempotent: CREATE TABLE IF NOT EXISTS, menu guarded by perms. Runs BEFORE the new jar.
--
-- ROLLBACK (the request table is evidence; archive it before any DROP):
--   DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE perms = 'business:moneyswitch:approve');
--   DELETE FROM sys_menu WHERE perms = 'business:moneyswitch:approve';
--   DROP TABLE IF EXISTS cy_money_switch_request;

CREATE TABLE IF NOT EXISTS cy_money_switch_request (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  area            VARCHAR(32)  NOT NULL,
  status          VARCHAR(16)  NOT NULL COMMENT 'PENDING | APPROVED | REJECTED | EXPIRED',
  requested_by    VARCHAR(64)  NOT NULL,
  request_reason  VARCHAR(255) NOT NULL,
  decided_by      VARCHAR(64)  NULL,
  decide_reason   VARCHAR(255) NULL,
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decide_time     DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_money_switch_request_area (area, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MERCHANT-15 two-person requests to open a money area (append, then decide once)';

SET @mstp_menu := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:moneyswitch:query' AND menu_type = 'C');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '资金开关打开批准', @mstp_menu, 2, '', '', '', '',
       1, 0, 'F', '0', '0', 'business:moneyswitch:approve', '#', 'migration', NOW(),
       'MERCHANT-15：批准别人提交的打开申请(不能批准自己的申请，须填写原因)'
FROM dual
WHERE @mstp_menu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:moneyswitch:approve');

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE()
      AND table_name = 'cy_money_switch_request') = 1
  AND (SELECT COUNT(1) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
        WHERE m.perms = 'business:moneyswitch:approve') = 0
) AS money_switch_two_person_ready;
