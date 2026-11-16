-- K1 club dues (slug club-dues, 2026-11-15): club dues paid to the club's own WeChat Pay sub-merchant (电商收付通二级商户)
-- with the platform's take shared by profit sharing (分账), never held in a member balance. Builds on the S2 collection
-- model: it acts only while the money switch's server ceiling (chengyin.money-switch.server-ceiling) is on, which is
-- false in production. With the ceiling off, dues behave exactly as before and this table stays empty.
--
-- Adds:
--   cy_collection_dues_order  one row per dues invoice ordered on the club's sub-merchant: club, sub-merchant, amount,
--                             the platform take rate frozen when the order was placed, and the profit-sharing order.
-- No existing table is altered. Nothing is inserted. Idempotent.
--
-- ROLLBACK (only while cy_collection_dues_order is empty; otherwise keep it, it is money evidence):
--   DROP TABLE IF EXISTS cy_collection_dues_order;

CREATE TABLE IF NOT EXISTS cy_collection_dues_order (
  out_trade_no     VARCHAR(64)   CHARACTER SET ascii NOT NULL COMMENT 'club_membership_invoice.out_trade_no = ecom_order_seller.out_trade_no',
  invoice_id       BIGINT        NOT NULL,
  club_id          BIGINT        NOT NULL,
  sub_mchid        VARCHAR(32)   CHARACTER SET ascii NOT NULL,
  total_fen        BIGINT        NOT NULL,
  take_rate        DECIMAL(10,6) NULL COMMENT 'platform take rate frozen when the order was placed (TakeRateService#clubDuesRate)',
  state            VARCHAR(16)   CHARACTER SET ascii NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN | PLANNED | REFUNDED',
  share_order_id   BIGINT        NULL COMMENT 'ecom_profit_share_order.id once planned',
  note             VARCHAR(64)   NULL,
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (out_trade_no),
  UNIQUE KEY uk_cy_collection_dues_invoice (invoice_id),
  KEY idx_cy_collection_dues_state (state, created_at),
  KEY idx_cy_collection_dues_club (club_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K1 club dues collected by the club sub-merchant (split settlement)';

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE()
      AND table_name = 'cy_collection_dues_order') = 1
) AS club_dues_ready;
