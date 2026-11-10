-- l-payout 2026-11-10：电商收付通（二级商户进件、按卖家下单、分账、分账回退）与创作者打款请求。
-- Additive only. 新表只记录微信侧的事实与执行意图；分账份额的真源仍是 coop_settlement / coop_settlement_original_component。
-- 所有写入路径都在 payout.ecommerce.* / payout.creator.* 开关之后，默认关闭。开发/测试执行与生产执行分别审批。

CREATE TABLE IF NOT EXISTS ecom_sub_merchant (
  id bigint NOT NULL AUTO_INCREMENT,
  owner_type varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'MERCHANT=mms_merchant.id, CLUB=club.id',
  owner_id bigint NOT NULL,
  out_request_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '平台进件业务申请编号，进件幂等键',
  applyment_id bigint NULL COMMENT '微信支付申请单号',
  applyment_state varchar(32) CHARACTER SET ascii NOT NULL COMMENT 'DRAFT/CHECKING/ACCOUNT_NEED_VERIFY/AUDITING/REJECTED/NEED_SIGN/FINISH/FROZEN/CANCELED',
  applyment_state_desc varchar(255) NULL,
  sign_url varchar(512) NULL,
  sub_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '仅在微信返回 FINISH 后写入',
  last_queried_at datetime(3) NULL,
  created_by varchar(64) NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ecom_sub_merchant_owner (owner_type, owner_id),
  UNIQUE KEY uk_ecom_sub_merchant_request (out_request_no),
  UNIQUE KEY uk_ecom_sub_merchant_mchid (sub_mchid),
  CONSTRAINT ck_ecom_sub_merchant_owner CHECK (owner_type IN ('MERCHANT','CLUB') AND owner_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='电商收付通二级商户进件';

CREATE TABLE IF NOT EXISTS ecom_order_seller (
  out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务支付单号（cms_registration.registration_no）',
  sub_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  seller_type varchar(16) CHARACTER SET ascii NOT NULL,
  seller_id bigint NOT NULL,
  total_fen bigint NOT NULL,
  transaction_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (out_trade_no),
  KEY idx_ecom_order_seller (seller_type, seller_id),
  CONSTRAINT ck_ecom_order_seller_total CHECK (total_fen > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='以二级商户为卖家下单的支付单';

CREATE TABLE IF NOT EXISTS ecom_profit_share_order (
  id bigint NOT NULL AUTO_INCREMENT,
  out_order_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '分账单号，分账幂等键',
  out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  topic_id bigint NOT NULL,
  sub_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  transaction_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  total_fen bigint NOT NULL,
  shared_fen bigint NOT NULL,
  plan_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'PLANNED/PROCESSING/SUCCESS/CLOSED/MANUAL',
  manual_reason varchar(64) CHARACTER SET ascii NULL,
  wx_order_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  attempts int NOT NULL DEFAULT 0,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ecom_share_out_order (out_order_no),
  UNIQUE KEY uk_ecom_share_trade_topic (out_trade_no, topic_id),
  CONSTRAINT ck_ecom_share_amount CHECK (total_fen > 0 AND shared_fen >= 0 AND shared_fen <= total_fen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='按支付单的分账请求';

CREATE TABLE IF NOT EXISTS ecom_profit_share_line (
  id bigint NOT NULL AUTO_INCREMENT,
  share_order_id bigint NOT NULL,
  settlement_id bigint NOT NULL COMMENT 'coop_settlement.id（份额真源）',
  line_type varchar(32) CHARACTER SET ascii NOT NULL,
  receiver_kind varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'PLATFORM/SUB_MERCHANT/RETAINED',
  receiver_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  receiver_member_id bigint NULL,
  amount_fen bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ecom_share_line (share_order_id, settlement_id, receiver_kind),
  CONSTRAINT fk_ecom_share_line_order FOREIGN KEY (share_order_id) REFERENCES ecom_profit_share_order (id),
  CONSTRAINT ck_ecom_share_line_amount CHECK (amount_fen >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分账明细（逐结算行）';

CREATE TABLE IF NOT EXISTS ecom_profit_share_return (
  id bigint NOT NULL AUTO_INCREMENT,
  out_return_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '回退单号，回退幂等键',
  share_order_id bigint NOT NULL,
  out_refund_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  return_mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_fen bigint NOT NULL,
  state varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'PROCESSING/SUCCESS/FAILED',
  wx_return_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  fail_reason varchar(64) CHARACTER SET ascii NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ecom_share_return_no (out_return_no),
  UNIQUE KEY uk_ecom_share_return_refund (share_order_id, out_refund_no, return_mchid),
  CONSTRAINT fk_ecom_share_return_order FOREIGN KEY (share_order_id) REFERENCES ecom_profit_share_order (id),
  CONSTRAINT ck_ecom_share_return_amount CHECK (amount_fen > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款前的分账回退';

CREATE TABLE IF NOT EXISTS creator_payout_request (
  id bigint NOT NULL AUTO_INCREMENT,
  out_payout_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  creator_member_id bigint NOT NULL,
  source_type varchar(32) CHARACTER SET ascii NOT NULL,
  source_id bigint NOT NULL,
  amount_fen bigint NOT NULL,
  provider varchar(32) CHARACTER SET ascii NOT NULL,
  state varchar(16) CHARACTER SET ascii NOT NULL COMMENT 'REQUESTED/SUBMITTED/SUCCEEDED/FAILED',
  provider_ref varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  fail_reason varchar(64) CHARACTER SET ascii NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_creator_payout_no (out_payout_no),
  UNIQUE KEY uk_creator_payout_source (source_type, source_id, creator_member_id),
  CONSTRAINT ck_creator_payout_amount CHECK (amount_fen > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='创作者打款请求（灵活用工等外部服务商）';
