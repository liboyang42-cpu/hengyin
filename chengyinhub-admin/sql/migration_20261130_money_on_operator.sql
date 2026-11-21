-- ================= migration_20261130_money_on_operator.sql =================
-- W7 x-money-on-readiness (MERCHANT-12): money-on operator dead ends.
-- 1. cy_merchant_plan_order gets payment_reference + amount_received_fen: confirming a plan order as paid now needs
--    the bank or WeChat reference and the amount received (must equal the order amount). Older rows stay NULL.
-- 2. cy_dues_refund_decision: one decision per club dues refund review (APPROVED / REJECTED, note, who, when) and,
--    for an approval, the refund request sent through the existing WeChat refund path (only while the Q5 money
--    switch area `refunds` is open). The review row itself is not changed (its CHECK keeps PENDING_REVIEW).
--    No member personal data: the member is reached through club_membership_refund_review.
-- 3. Two console buttons, granted to NO role: business:merchantplans:grantPaid (grant a paid plan without an order)
--    and business:commerceConsole:refund (approve or reject a dues refund).
-- No production switch is written. Idempotent: guarded ALTERs, CREATE TABLE IF NOT EXISTS, menus guarded by perms.
-- Runs BEFORE the new jar (the new mappers read these columns and this table).
--
-- ROLLBACK (only while cy_dues_refund_decision is empty; it is a money record otherwise, archive it):
--   DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu
--       WHERE perms IN ('business:merchantplans:grantPaid', 'business:commerceConsole:refund'));
--   DELETE FROM sys_menu WHERE perms IN ('business:merchantplans:grantPaid', 'business:commerceConsole:refund');
--   DROP TABLE IF EXISTS cy_dues_refund_decision;
--   ALTER TABLE cy_merchant_plan_order DROP COLUMN payment_reference, DROP COLUMN amount_received_fen;

SET @mor_ref := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cy_merchant_plan_order' AND column_name = 'payment_reference');
SET @mor_ref_sql := IF(@mor_ref = 0,
    'ALTER TABLE cy_merchant_plan_order ADD COLUMN payment_reference VARCHAR(64) NULL COMMENT ''MERCHANT-12: bank / WeChat reference of the payment received'' AFTER confirmed_by',
    'SELECT 1');
PREPARE mor_ref_stmt FROM @mor_ref_sql;
EXECUTE mor_ref_stmt;
DEALLOCATE PREPARE mor_ref_stmt;

SET @mor_amt := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cy_merchant_plan_order' AND column_name = 'amount_received_fen');
SET @mor_amt_sql := IF(@mor_amt = 0,
    'ALTER TABLE cy_merchant_plan_order ADD COLUMN amount_received_fen BIGINT NULL COMMENT ''MERCHANT-12: amount received, equal to amount_fen'' AFTER payment_reference',
    'SELECT 1');
PREPARE mor_amt_stmt FROM @mor_amt_sql;
EXECUTE mor_amt_stmt;
DEALLOCATE PREPARE mor_amt_stmt;

CREATE TABLE IF NOT EXISTS cy_dues_refund_decision (
  review_id         BIGINT       NOT NULL COMMENT 'club_membership_refund_review.id',
  decision          VARCHAR(16)  NOT NULL COMMENT 'APPROVED | REJECTED',
  note              VARCHAR(500) NOT NULL COMMENT 'operator note (required)',
  decided_by        VARCHAR(64)  NOT NULL,
  decided_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  out_refund_no     VARCHAR(64)  NULL     COMMENT 'deterministic refund number (WeChat is idempotent on it)',
  refund_amount_fen BIGINT       NULL,
  refund_state      VARCHAR(16)  NOT NULL COMMENT 'NOT_SENT | SUBMITTING | ACCEPTED | FAILED | UNKNOWN',
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (review_id),
  UNIQUE KEY uk_dues_refund_out_no (out_refund_no),
  KEY idx_dues_refund_state (refund_state, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MERCHANT-12 club dues refund decisions and the refund request sent';

SET @mor_plans := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:merchantplans:query' AND menu_type = 'C');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '无订单开通付费套餐', @mor_plans, 5, '', '', '', '', 1, 0, 'F', '0', '0', 'business:merchantplans:grantPaid', '#',
       'migration', NOW(), 'MERCHANT-12：不经订单直接开通付费套餐(另需「套餐开通与收款确认」，资金开关关闭时拒绝)'
FROM dual WHERE @mor_plans IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantplans:grantPaid');

SET @mor_commerce := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:commerceConsole:list' AND menu_type = 'C');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '会费退款处理', @mor_commerce, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'business:commerceConsole:refund', '#',
       'migration', NOW(), 'MERCHANT-12：通过(原路退款，资金开关 refunds 打开时)或驳回会费退款申请(须填写说明)'
FROM dual WHERE @mor_commerce IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:commerceConsole:refund');

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
      AND table_name = 'cy_merchant_plan_order' AND column_name IN ('payment_reference', 'amount_received_fen')) = 2
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
      AND table_name = 'cy_dues_refund_decision') = 1
  AND (SELECT COUNT(1) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
        WHERE m.perms IN ('business:merchantplans:grantPaid', 'business:commerceConsole:refund')) = 0
) AS money_on_operator_ready;
