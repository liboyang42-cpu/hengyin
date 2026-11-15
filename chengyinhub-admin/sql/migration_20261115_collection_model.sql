-- S2 collection model (slug collection-model, 2026-11-15).
-- Owner ruling 2026-09-15, confirmed 2026-11-15: the platform collects only for its own official tickets; every
-- third-party seller (club host, merchant) is paid through single-seller split settlement (电商收付通 / 服务商分账),
-- never collect-then-redistribute (二清). Legal confirmation pending: the model only acts while the money switch's
-- server ceiling (chengyin.money-switch.server-ceiling) is on, which is false in production.
--
-- Adds:
--   cy_collection_order    one row per ticket order placed under the model with the SPLIT policy: seller, sub-merchant,
--                          frozen platform take rate, and the profit-sharing order it settled into (ledger source).
--   cy_collection_refusal  one row per (seller, reason): how many buyers were refused because the seller cannot be
--                          paid by split settlement yet (ops follow-up list for sub-merchant binding).
--   admin menu 分账收款 (business:collection:query), and one button (business:collection:edit) for replan/retry,
--   both granted to no role.
-- No existing table is altered. Nothing is inserted except menu rows. Idempotent.
--
-- ROLLBACK (only while cy_collection_order is empty; otherwise keep it, it is money evidence):
--   DELETE FROM sys_menu WHERE perms IN ('business:collection:edit');
--   DELETE FROM sys_menu WHERE perms IN ('business:collection:query');
--   DROP TABLE IF EXISTS cy_collection_refusal;
--   DROP TABLE IF EXISTS cy_collection_order;

CREATE TABLE IF NOT EXISTS cy_collection_order (
  registration_no  VARCHAR(64)   CHARACTER SET ascii NOT NULL COMMENT 'cms_registration.registration_no = ecom_order_seller.out_trade_no',
  registration_id  BIGINT        NOT NULL,
  owner_type       TINYINT       NOT NULL COMMENT '1 topic ticket, 2 activity ticket',
  owner_id         BIGINT        NOT NULL,
  seller_type      VARCHAR(16)   CHARACTER SET ascii NOT NULL COMMENT 'MERCHANT | CLUB',
  seller_id        BIGINT        NOT NULL,
  sub_mchid        VARCHAR(32)   CHARACTER SET ascii NOT NULL,
  total_fen        BIGINT        NOT NULL,
  take_rate        DECIMAL(10,6) NULL COMMENT 'platform take rate frozen on the order (cms_registration.fee_platform_rate)',
  state            VARCHAR(16)   CHARACTER SET ascii NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN | PLANNED | REFUNDED | COOP',
  share_order_id   BIGINT        NULL COMMENT 'ecom_profit_share_order.id once planned',
  note             VARCHAR(64)   NULL,
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (registration_no),
  UNIQUE KEY uk_cy_collection_order_registration (registration_id),
  KEY idx_cy_collection_order_state (state, created_at),
  KEY idx_cy_collection_order_owner (owner_type, owner_id),
  KEY idx_cy_collection_order_seller (seller_type, seller_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S2 collection model: ticket orders collected by the seller sub-merchant (split settlement)';

CREATE TABLE IF NOT EXISTS cy_collection_refusal (
  seller_type   VARCHAR(16)  CHARACTER SET ascii NOT NULL COMMENT 'MERCHANT | CLUB | MEMBER | UNKNOWN | DUES',
  seller_id     BIGINT       NOT NULL DEFAULT 0,
  reason        VARCHAR(32)  CHARACTER SET ascii NOT NULL,
  refused_count BIGINT       NOT NULL DEFAULT 0,
  first_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (seller_type, seller_id, reason),
  KEY idx_cy_collection_refusal_last (last_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S2 collection model: buyers refused because the seller cannot be paid by split settlement yet';

SET @collectionParentCount := (SELECT COUNT(1) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @collectionParent := (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @collectionExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:collection:query');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '分账收款', @collectionParent, 81, 'collection-model', 'business/collection/index', '', 'CollectionModel',
       1, 0, 'C', '0', '0', 'business:collection:query', 'money', 'migration', NOW(),
       'S2：收款模式(官方票平台收款，第三方单一卖家分账)、卖家收款状态、分账台账、失败队列'
FROM dual
WHERE @collectionParentCount = 1 AND @collectionExisting = 0;

SET @collectionMenu := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:collection:query' AND menu_type = 'C');
SET @collectionEditExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:collection:edit');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '分账收款处理', @collectionMenu, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'business:collection:edit', '#', 'migration', NOW(),
       'S2：重新计划转人工的分账单、重试失败的分账回退'
FROM dual
WHERE @collectionMenu IS NOT NULL AND @collectionEditExisting = 0;

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE()
      AND table_name IN ('cy_collection_order', 'cy_collection_refusal')) = 2
  AND (SELECT COUNT(1) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
        WHERE m.perms IN ('business:collection:query', 'business:collection:edit')) = 0
) AS collection_model_ready;
