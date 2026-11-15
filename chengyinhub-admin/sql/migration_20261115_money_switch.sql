-- Q5 money switch (slug money-switch, 2026-11-15).
-- Adds the per-area money switch read by GET /api/app/money-switch and its append-only audit trail,
-- plus the admin console menu (business:moneyswitch:query / :edit), granted to no role.
-- Every area starts OFF: no rows are inserted, and a missing row reads off.
-- Idempotent: CREATE TABLE IF NOT EXISTS, menu rows guarded by existence checks.
--
-- ROLLBACK (only if no area was ever switched on; keep the audit table if it has rows):
--   DELETE FROM sys_menu WHERE perms IN ('business:moneyswitch:edit');
--   DELETE FROM sys_menu WHERE perms IN ('business:moneyswitch:query');
--   DROP TABLE IF EXISTS cy_money_switch;
--   -- cy_money_switch_audit is evidence; archive it before any DROP.

CREATE TABLE IF NOT EXISTS cy_money_switch (
  area        VARCHAR(32)  NOT NULL COMMENT 'tickets | pass | merchantMoney | payout | withdrawal | refunds',
  enabled     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 = on; anything else reads off',
  update_by   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'admin user name of the last change',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (area)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Q5 money switch, per area, default off';

CREATE TABLE IF NOT EXISTS cy_money_switch_audit (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  area        VARCHAR(32)  NOT NULL,
  old_enabled TINYINT(1)   NOT NULL,
  new_enabled TINYINT(1)   NOT NULL,
  changed_by  VARCHAR(64)  NOT NULL,
  reason      VARCHAR(255) NOT NULL,
  changed_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_money_switch_audit_area (area, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Q5 money switch audit: who, when, old, new, reason (append only)';

SET @moneySwitchParentCount := (SELECT COUNT(1) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @moneySwitchParent := (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @moneySwitchExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:moneyswitch:query');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '资金开关', @moneySwitchParent, 80, 'money-switch', 'business/moneyswitch/index', '', 'MoneySwitch',
       1, 0, 'C', '0', '0', 'business:moneyswitch:query', 'lock', 'migration', NOW(),
       'Q5：App 资金区域开关(默认全关，受服务端上限约束，每次改动留审计)'
FROM dual
WHERE @moneySwitchParentCount = 1 AND @moneySwitchExisting = 0;

SET @moneySwitchMenu := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:moneyswitch:query' AND menu_type = 'C');
SET @moneySwitchEditExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:moneyswitch:edit');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '资金开关修改', @moneySwitchMenu, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'business:moneyswitch:edit', '#', 'migration', NOW(),
       'Q5：打开或关闭一个资金区域(须填写原因)'
FROM dual
WHERE @moneySwitchMenu IS NOT NULL AND @moneySwitchEditExisting = 0;

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE()
      AND table_name IN ('cy_money_switch', 'cy_money_switch_audit')) = 2
  AND (SELECT COUNT(1) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
        WHERE m.perms IN ('business:moneyswitch:query', 'business:moneyswitch:edit')) = 0
) AS money_switch_ready;
