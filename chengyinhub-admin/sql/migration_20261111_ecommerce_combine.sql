-- l-payout3 2026-11-11：多方收款的主题门票改用电商收付通「合单支付」（按收款方拆子单）与平台供应商结算。
-- 类别：③ 数据迁移。Additive only：只新建 6 张表，不改任何已有表与数据。
-- 依赖：migration_20261110_ecommerce_payout_wiring.sql（同一组 ecom_* 表；本迁移不引用它们的列，只保证执行顺序）。
-- 所有写入路径都在 payout.ecommerce.combined-enabled 开关之后，默认关闭；开关关闭时没有任何代码读写这些表。
-- 份额真源仍是 coop_settlement / coop_settlement_original_component；这些表只记录下单拆分、微信侧事实与执行意图。

CREATE TABLE IF NOT EXISTS ecom_combine_order (
  out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务支付单号（cms_registration.registration_no）',
  seller_sub_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '卖家子单收款二级商户号',
  seller_type varchar(16) CHARACTER SET ascii NOT NULL,
  seller_id bigint NOT NULL,
  platform_sub_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '平台自营二级商户号（平台为卖家子单）',
  total_fen bigint NOT NULL,
  seller_fen bigint NOT NULL,
  held_fen bigint NOT NULL COMMENT '平台自营子单 = 平台佣金 + 预留',
  commission_fen bigint NOT NULL,
  reserve_fen bigint NOT NULL,
  combine_out_trade_no varchar(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '已支付的合单号',
  seller_transaction_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '已支付卖家子单的微信订单号',
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (out_trade_no),
  KEY idx_ecom_combine_order_seller (seller_type, seller_id),
  CONSTRAINT ck_ecom_combine_order_amount CHECK (seller_fen > 0 AND held_fen > 0 AND total_fen = seller_fen + held_fen
      AND commission_fen >= 0 AND reserve_fen >= 0 AND held_fen = commission_fen + reserve_fen),
  CONSTRAINT ck_ecom_combine_order_payees CHECK (seller_sub_mchid <> platform_sub_mchid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='合单支付的业务单（下单时按收款方拆分）';

CREATE TABLE IF NOT EXISTS ecom_combine_sub_order (
  id bigint NOT NULL AUTO_INCREMENT,
  combine_out_trade_no varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'SELLER/PLATFORM_HELD',
  out_trade_no varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '子单商户订单号',
  business_out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sub_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_fen bigint NOT NULL,
  profit_sharing tinyint NOT NULL,
  transaction_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ecom_combine_sub_role (combine_out_trade_no, role),
  UNIQUE KEY uk_ecom_combine_sub_no (out_trade_no),
  KEY idx_ecom_combine_sub_business (business_out_trade_no),
  CONSTRAINT ck_ecom_combine_sub CHECK (role IN ('SELLER','PLATFORM_HELD') AND amount_fen > 0 AND profit_sharing IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='合单子单（每个收款方一笔）';

CREATE TABLE IF NOT EXISTS ecom_combine_refund (
  out_refund_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '子单退款单号（由业务退款单号与角色确定性生成）',
  parent_out_refund_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务退款单号（refund_application.refund_no）',
  business_out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role varchar(16) CHARACTER SET ascii NOT NULL,
  sub_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sub_out_trade_no varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  transaction_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  refund_fen bigint NOT NULL,
  total_fen bigint NOT NULL COMMENT '子单实付',
  state varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'RETURNING/RETURN_FAILED/SUBMITTING/SUBMITTED/SUCCESS/CLOSED/ABNORMAL',
  refund_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  fail_reason varchar(64) CHARACTER SET ascii NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (out_refund_no),
  UNIQUE KEY uk_ecom_combine_refund_parent (parent_out_refund_no, role),
  KEY idx_ecom_combine_refund_trade (business_out_trade_no),
  KEY idx_ecom_combine_refund_state (state),
  CONSTRAINT ck_ecom_combine_refund CHECK (role IN ('SELLER','PLATFORM_HELD') AND refund_fen > 0 AND refund_fen <= total_fen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='合单的退款：每个子单单独退款';

CREATE TABLE IF NOT EXISTS ecom_combine_settlement (
  id bigint NOT NULL AUTO_INCREMENT,
  out_order_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '卖家子单的分账 / 完结分账单号',
  business_out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  topic_id bigint NOT NULL,
  seller_sub_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  seller_transaction_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  seller_fen bigint NOT NULL,
  held_fen bigint NOT NULL,
  platform_fen bigint NOT NULL COMMENT '账本平台佣金',
  shortfall_fen bigint NOT NULL COMMENT '预留不够、需从卖家子单分给平台的差额',
  surplus_fen bigint NOT NULL COMMENT '预留多出、平台按供应商结算付回卖家的部分',
  plan_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'PLANNED/PROCESSING/SUCCESS/CLOSED/MANUAL',
  manual_reason varchar(64) CHARACTER SET ascii NULL,
  wx_order_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ecom_combine_settle_order (out_order_no),
  UNIQUE KEY uk_ecom_combine_settle_trade (business_out_trade_no, topic_id),
  KEY idx_ecom_combine_settle_state (state),
  CONSTRAINT ck_ecom_combine_settle CHECK (shortfall_fen >= 0 AND surplus_fen >= 0 AND shortfall_fen <= seller_fen
      AND (shortfall_fen = 0 OR surplus_fen = 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='合单结算：卖家子单补差分账或完结';

CREATE TABLE IF NOT EXISTS ecom_combine_return (
  out_return_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  combine_settlement_id bigint NOT NULL,
  parent_out_refund_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  return_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_fen bigint NOT NULL,
  state varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'PROCESSING/SUCCESS/FAILED',
  wx_return_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  fail_reason varchar(64) CHARACTER SET ascii NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (out_return_no),
  UNIQUE KEY uk_ecom_combine_return_refund (combine_settlement_id, parent_out_refund_no),
  CONSTRAINT ck_ecom_combine_return CHECK (amount_fen > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='卖家子单补差分账的退款前回退';

CREATE TABLE IF NOT EXISTS ecom_supplier_payable (
  id bigint NOT NULL AUTO_INCREMENT,
  source varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'COMBINED=合单平台自营子单 / RELAY=单商户单经平台分账转付',
  business_out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  topic_id bigint NOT NULL,
  settlement_id bigint NOT NULL COMMENT 'coop_settlement 结算头；SURPLUS 行记卖家份额所在结算头',
  line_type varchar(32) CHARACTER SET ascii NOT NULL COMMENT 'LEAD_FEE/MERCHANT_VERIFY/INITIATOR_*（SURPLUS=预留退回卖家）',
  payee_member_id bigint NOT NULL,
  amount_fen bigint NOT NULL,
  state varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'PENDING/PAID/HOLD_REFUND/CANCELLED',
  paid_reference varchar(128) NULL COMMENT '线下对公付款 / 灵活用工打款单号（运营录入）',
  paid_by varchar(64) NULL,
  paid_at datetime(3) NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ecom_supplier_payable (source, business_out_trade_no, topic_id, settlement_id, payee_member_id, line_type),
  KEY idx_ecom_supplier_payable_state (state),
  KEY idx_ecom_supplier_payable_payee (payee_member_id),
  CONSTRAINT ck_ecom_supplier_payable CHECK (source IN ('COMBINED','RELAY') AND amount_fen > 0 AND payee_member_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台为卖家的份额：按供应商结算付给核销商家 / 带队俱乐部（不记会员余额）';

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema=DATABASE() AND table_type='BASE TABLE'
          AND table_name IN ('ecom_combine_order','ecom_combine_sub_order','ecom_combine_refund',
                             'ecom_combine_settlement','ecom_combine_return','ecom_supplier_payable'))=6;
