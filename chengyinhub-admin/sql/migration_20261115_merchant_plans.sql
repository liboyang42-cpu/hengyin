-- S3a merchant plans (slug merchant-plans, 2026-11-15). MON-04 / MON-07 / MON-15.
-- 1. cy_take_rate: the platform take per product type (official_ticket, third_party_ticket by source, bundle,
--    template). Starts EMPTY: a product without a row keeps today's value (settlement_rule / theme snapshot /
--    creator share), so nothing changes until someone edits a row in the console. Changes are audited in
--    business_audit_event (PLATFORM, TAKE_RATE_SET).
-- 2. cy_merchant_plan: configured plans with feature flags. Seeds Free (today's features), Basic ¥199 and Pro ¥499
--    a month (the 05 report's hypothesis), and a per-visit plan that is NOT offered and has no fee until the owner
--    sets one.
-- 3. cy_merchant_plan_subscription / cy_merchant_plan_order: current plan and plan orders. No rows inserted:
--    every merchant is on Free.
-- 4. cy_merchant_visit_charge / cy_merchant_visit_invoice: verified-visit billing ledger and monthly invoices.
-- 5. Console menu 商家套餐与抽成 under 资金与售后, five permissions, granted to no role.
-- Charges (purchase, renewal, paid grants, visit accrual, invoices) run only while money switch area merchantMoney
-- is on; this file does not touch cy_money_switch.
-- Idempotent: CREATE TABLE IF NOT EXISTS, seed and menu rows guarded by existence checks.
--
-- ROLLBACK (only while no plan was sold and no visit was charged; archive the ledger tables otherwise):
--   DELETE FROM sys_menu WHERE perms IN ('business:merchantplans:edit', 'business:merchantplans:take',
--          'business:merchantplans:sell', 'business:merchantplans:billing');
--   DELETE FROM sys_menu WHERE perms = 'business:merchantplans:query';
--   DROP TABLE IF EXISTS cy_merchant_visit_invoice;
--   DROP TABLE IF EXISTS cy_merchant_visit_charge;
--   DROP TABLE IF EXISTS cy_merchant_plan_order;
--   DROP TABLE IF EXISTS cy_merchant_plan_subscription;
--   DROP TABLE IF EXISTS cy_merchant_plan;
--   DROP TABLE IF EXISTS cy_take_rate;   -- an empty table means every take is today's value

CREATE TABLE IF NOT EXISTS cy_take_rate (
  product_type VARCHAR(32)  NOT NULL COMMENT 'official_ticket | third_party_ticket | bundle | template',
  source       VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'third_party_ticket: club | organiser; empty = every source',
  rate_bps     INT          NOT NULL COMMENT 'platform take in basis points, 0..10000',
  update_by    VARCHAR(64)  NOT NULL DEFAULT '',
  update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (product_type, source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S3a configured platform take; no row = today''s value';

CREATE TABLE IF NOT EXISTS cy_merchant_plan (
  code              VARCHAR(16)   NOT NULL COMMENT 'FREE | BASIC | PRO | PER_VISIT',
  name_zh           VARCHAR(32)   NOT NULL,
  name_en           VARCHAR(32)   NOT NULL,
  monthly_price_fen INT           NOT NULL DEFAULT 0,
  visit_fee_fen     INT           NULL COMMENT 'set = per-visit plan; fee per staff-verified visit',
  features_json     VARCHAR(1000) NOT NULL,
  offered           TINYINT(1)    NOT NULL DEFAULT 1,
  sort_order        INT           NOT NULL DEFAULT 0,
  update_by         VARCHAR(64)   NOT NULL DEFAULT '',
  update_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S3a merchant plans as configured products';

CREATE TABLE IF NOT EXISTS cy_merchant_plan_subscription (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  merchant_id  BIGINT       NOT NULL COMMENT 'mms_merchant.id',
  plan_code    VARCHAR(16)  NOT NULL,
  period_start DATETIME     NOT NULL,
  period_end   DATETIME     NOT NULL,
  source       VARCHAR(16)  NOT NULL COMMENT 'PURCHASE | ADMIN',
  order_no     VARCHAR(40)  NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | ENDED',
  create_by    VARCHAR(64)  NOT NULL DEFAULT '',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_mps_merchant (merchant_id, status, period_end),
  KEY idx_mps_period (period_start, period_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S3a merchant plan periods; no active row = Free';

CREATE TABLE IF NOT EXISTS cy_merchant_plan_order (
  order_no     VARCHAR(40)  NOT NULL,
  merchant_id  BIGINT       NOT NULL,
  member_id    BIGINT       NOT NULL COMMENT 'shop owner who asked',
  plan_code    VARCHAR(16)  NOT NULL,
  months       INT          NOT NULL,
  amount_fen   BIGINT       NOT NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING | PAID | CANCELLED',
  request_id   VARCHAR(64)  NOT NULL,
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_time    DATETIME     NULL,
  confirmed_by VARCHAR(64)  NULL,
  PRIMARY KEY (order_no),
  UNIQUE KEY uk_mpo_request (merchant_id, request_id),
  KEY idx_mpo_status (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S3a merchant plan orders (operations confirm payment)';

CREATE TABLE IF NOT EXISTS cy_merchant_visit_charge (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  merchant_id   BIGINT       NOT NULL,
  member_id     BIGINT       NOT NULL COMMENT 'player with a staff verification that day',
  visit_day     DATE         NOT NULL COMMENT 'Asia/Shanghai local day',
  plan_code     VARCHAR(16)  NOT NULL,
  fee_fen       INT          NOT NULL,
  invoice_month CHAR(7)      NOT NULL COMMENT 'yyyy-MM',
  invoice_id    BIGINT       NULL,
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mvc_visit (merchant_id, member_id, visit_day),
  KEY idx_mvc_month (invoice_month, merchant_id, invoice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S3a one row per staff-verified visit on a per-visit plan';

CREATE TABLE IF NOT EXISTS cy_merchant_visit_invoice (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  merchant_id   BIGINT       NOT NULL,
  invoice_month CHAR(7)      NOT NULL,
  visit_count   BIGINT       NOT NULL,
  amount_fen    BIGINT       NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ISSUED',
  issued_by     VARCHAR(64)  NOT NULL,
  issued_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mvi_month (merchant_id, invoice_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S3a monthly verified-visit invoices';

INSERT INTO cy_merchant_plan (code, name_zh, name_en, monthly_price_fen, visit_fee_fen, features_json, offered, sort_order, update_by)
SELECT 'FREE', '免费版', 'Free', 0, NULL,
       '{"weeklyProofDetail":false,"extraNodeGames":0,"dealsOffersPerMonth":0}', 1, 0, 'migration'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM cy_merchant_plan WHERE code = 'FREE');
INSERT INTO cy_merchant_plan (code, name_zh, name_en, monthly_price_fen, visit_fee_fen, features_json, offered, sort_order, update_by)
SELECT 'BASIC', '基础版', 'Basic', 19900, NULL,
       '{"weeklyProofDetail":true,"extraNodeGames":2,"dealsOffersPerMonth":2}', 1, 10, 'migration'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM cy_merchant_plan WHERE code = 'BASIC');
INSERT INTO cy_merchant_plan (code, name_zh, name_en, monthly_price_fen, visit_fee_fen, features_json, offered, sort_order, update_by)
SELECT 'PRO', '专业版', 'Pro', 49900, NULL,
       '{"weeklyProofDetail":true,"extraNodeGames":5,"dealsOffersPerMonth":8}', 1, 20, 'migration'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM cy_merchant_plan WHERE code = 'PRO');
INSERT INTO cy_merchant_plan (code, name_zh, name_en, monthly_price_fen, visit_fee_fen, features_json, offered, sort_order, update_by)
SELECT 'PER_VISIT', '按到店付费', 'Pay per visit', 0, NULL,
       '{"weeklyProofDetail":true,"extraNodeGames":0,"dealsOffersPerMonth":0}', 0, 30, 'migration'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM cy_merchant_plan WHERE code = 'PER_VISIT');

SET @merchantPlansParentCount := (SELECT COUNT(1) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @merchantPlansParent := (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @merchantPlansExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:merchantplans:query');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '商家套餐与抽成', @merchantPlansParent, 81, 'merchant-plans', 'business/merchantplans/index', '', 'MerchantPlans',
       1, 0, 'C', '0', '0', 'business:merchantplans:query', 'shopping', 'migration', NOW(),
       'S3a：平台抽成按产品类型设置、商家套餐、到店计费(收费部分受资金开关约束)'
FROM dual
WHERE @merchantPlansParentCount = 1 AND @merchantPlansExisting = 0;

SET @merchantPlansMenu := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:merchantplans:query' AND menu_type = 'C');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '平台抽成修改', @merchantPlansMenu, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:merchantplans:take', '#',
       'migration', NOW(), 'S3a：修改某类产品的平台抽成(须填写原因，留审计)'
FROM dual WHERE @merchantPlansMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantplans:take');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '套餐设置修改', @merchantPlansMenu, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'business:merchantplans:edit', '#',
       'migration', NOW(), 'S3a：修改套餐价格、功能与是否在售(须填写原因，留审计)'
FROM dual WHERE @merchantPlansMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantplans:edit');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '套餐开通与收款确认', @merchantPlansMenu, 3, '', '', '', '', 1, 0, 'F', '0', '0', 'business:merchantplans:sell', '#',
       'migration', NOW(), 'S3a：开通、续费套餐，确认套餐订单已收款(资金开关关闭时拒绝)'
FROM dual WHERE @merchantPlansMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantplans:sell');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '到店计费入账与月结', @merchantPlansMenu, 4, '', '', '', '', 1, 0, 'F', '0', '0', 'business:merchantplans:billing', '#',
       'migration', NOW(), 'S3a：到店计费补入账、开具月度账单(资金开关关闭时不写入)'
FROM dual WHERE @merchantPlansMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantplans:billing');

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE()
      AND table_name IN ('cy_take_rate', 'cy_merchant_plan', 'cy_merchant_plan_subscription', 'cy_merchant_plan_order',
                         'cy_merchant_visit_charge', 'cy_merchant_visit_invoice')) = 6
  AND (SELECT COUNT(1) FROM cy_merchant_plan WHERE code = 'FREE' AND monthly_price_fen = 0 AND offered = 1) = 1
  AND (SELECT COUNT(1) FROM cy_merchant_plan WHERE code = 'PER_VISIT' AND offered = 0) = 1
  AND (SELECT COUNT(1) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
        WHERE m.perms LIKE 'business:merchantplans:%') = 0
) AS merchant_plans_ready;
