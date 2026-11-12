-- Additive only. Development/test execution and production execution are separate approvals.
-- Original money authority remains wx_pay_order_intent + wx_verified_payment_fact.
CREATE TABLE IF NOT EXISTS workshop_version (
  version_id varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  seller_id bigint NOT NULL,
  request_id varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  module_id bigint NOT NULL,
  source_canonical_id bigint NOT NULL,
  content_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content_json longtext NOT NULL,
  terms_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  terms_json text NOT NULL,
  price_fen bigint NOT NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(version_id), UNIQUE KEY uk_workshop_version_request(seller_id,request_id),
  CONSTRAINT ck_workshop_price CHECK(price_fen>0 AND price_fen<=2147483647)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workshop_order (
  id bigint NOT NULL AUTO_INCREMENT,
  order_no varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  buyer_id bigint NOT NULL,
  request_id varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version_id varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_fen bigint NOT NULL,
  provider varchar(20) CHARACTER SET ascii NOT NULL,
  state varchar(20) CHARACTER SET ascii NOT NULL,
  license_status varchar(20) CHARACTER SET ascii NOT NULL,
  payment_fact_id bigint NULL,
  payment_fact_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  paid_at datetime(3) NULL,
  refund_no varchar(44) CHARACTER SET ascii COLLATE ascii_bin NULL,
  refund_dispatch varchar(24) CHARACTER SET ascii NULL,
  refund_requested_at datetime(3) NULL,
  refund_fact_id bigint NULL,
  refund_fact_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  refunded_at datetime(3) NULL,
  revoked_at datetime(3) NULL,
  revoke_reason varchar(40) CHARACTER SET ascii NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id), UNIQUE KEY uk_workshop_order_no(order_no),
  UNIQUE KEY uk_workshop_order_request(buyer_id,request_id),
  UNIQUE KEY uk_workshop_payment_fact(payment_fact_id), UNIQUE KEY uk_workshop_refund_no(refund_no),
  KEY idx_workshop_licenses(buyer_id,id),
  CONSTRAINT fk_workshop_order_version FOREIGN KEY(version_id) REFERENCES workshop_version(version_id),
  CONSTRAINT ck_workshop_order_amount CHECK(amount_fen>0 AND amount_fen<=2147483647)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workshop_install (
  id bigint NOT NULL AUTO_INCREMENT,
  buyer_id bigint NOT NULL,
  request_id varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_no varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  command_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  command_json text NOT NULL,
  outcome_json text NULL,
  draft_id bigint NULL,
  installed_at datetime(3) NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id), UNIQUE KEY uk_workshop_install_request(buyer_id,request_id),
  UNIQUE KEY uk_workshop_install_draft(draft_id), KEY idx_workshop_install_order(order_no,id),
  CONSTRAINT fk_workshop_install_order FOREIGN KEY(order_no) REFERENCES workshop_order(order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
