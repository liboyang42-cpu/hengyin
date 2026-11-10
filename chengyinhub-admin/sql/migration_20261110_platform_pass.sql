-- l-pass 2026-11-10：通行证（平台会员，年卡/月卡，不自动续费）。只新增表，不改既有表。
-- 资金权威仍是 wx_pay_order_intent + wx_verified_payment_fact（JSAPI 渠道）；
-- 虚拟支付（xpay，iOS）渠道的支付事实以服务端 /xpay/query_order 回查结果落在本订单行。
-- 全部功能由 platform-pass.enabled 等开关控制，默认关闭；建表本身不改变任何现有业务结果。
-- 生产执行必须走 chengyin-db-migrate；可重复执行（IF NOT EXISTS / INSERT IGNORE）。

CREATE TABLE IF NOT EXISTS platform_pass_plan (
  id bigint NOT NULL AUTO_INCREMENT,
  plan_code varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'YEAR / MONTH',
  title varchar(32) COLLATE utf8mb4_general_ci NOT NULL,
  price_fen int NOT NULL,
  valid_days int NOT NULL,
  xpay_product_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '虚拟支付道具 ID（商户后台配置后回填）',
  status tinyint NOT NULL DEFAULT 0 COMMENT '0 未上架 / 1 在售',
  sort int NOT NULL DEFAULT 0,
  create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_pass_plan_code (plan_code),
  CONSTRAINT ck_platform_pass_plan_price CHECK (price_fen > 0),
  CONSTRAINT ck_platform_pass_plan_days CHECK (valid_days > 0 AND valid_days <= 400),
  CONSTRAINT ck_platform_pass_plan_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通行证套餐';

CREATE TABLE IF NOT EXISTS platform_pass_order (
  id bigint NOT NULL AUTO_INCREMENT,
  order_no varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  member_id bigint NOT NULL,
  request_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_fingerprint char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  plan_id bigint NOT NULL,
  plan_code varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  price_fen int NOT NULL COMMENT '下单时冻结',
  valid_days int NOT NULL COMMENT '下单时冻结',
  channel varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'JSAPI / XPAY',
  status tinyint NOT NULL DEFAULT 0 COMMENT '0 待支付 / 1 已支付 / 2 已取消 / 3 已退款 / 4 退款中',
  payment_fact_id bigint NULL COMMENT 'JSAPI：wx_verified_payment_fact.id',
  payment_fact_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  transaction_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'JSAPI transaction_id / XPAY wx_order_id',
  xpay_env tinyint NULL COMMENT '0 正式 / 1 沙箱',
  paid_time datetime(3) NULL,
  refund_no varchar(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  refund_reason varchar(128) COLLATE utf8mb4_general_ci NULL,
  refund_requested_by varchar(64) COLLATE utf8mb4_general_ci NULL,
  refund_requested_time datetime(3) NULL,
  refund_fact_id bigint NULL,
  refund_fact_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  refunded_time datetime(3) NULL,
  cancel_time datetime(3) NULL,
  create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_pass_order_no (order_no),
  UNIQUE KEY uk_platform_pass_order_request (member_id, request_id),
  UNIQUE KEY uk_platform_pass_order_refund_no (refund_no),
  KEY idx_platform_pass_order_member (member_id, status),
  CONSTRAINT ck_platform_pass_order_status CHECK (status IN (0, 1, 2, 3, 4)),
  CONSTRAINT ck_platform_pass_order_channel CHECK (channel IN ('JSAPI', 'XPAY')),
  CONSTRAINT ck_platform_pass_order_price CHECK (price_fen > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通行证订单';

CREATE TABLE IF NOT EXISTS platform_pass_membership (
  id bigint NOT NULL AUTO_INCREMENT,
  member_id bigint NOT NULL,
  order_id bigint NOT NULL,
  valid_from datetime(3) NOT NULL,
  valid_to datetime(3) NOT NULL,
  status tinyint NOT NULL DEFAULT 1 COMMENT '1 有效 / 3 已撤销（退款）',
  create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_pass_membership_order (order_id),
  KEY idx_platform_pass_membership_member (member_id, status, valid_to),
  CONSTRAINT ck_platform_pass_membership_window CHECK (valid_to > valid_from),
  CONSTRAINT ck_platform_pass_membership_status CHECK (status IN (1, 3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通行证有效期（每笔已付订单一段，续买顺延）';

CREATE TABLE IF NOT EXISTS platform_pass_claim (
  id bigint NOT NULL AUTO_INCREMENT,
  membership_id bigint NOT NULL,
  member_id bigint NOT NULL,
  topic_id bigint NOT NULL,
  registration_id bigint NOT NULL,
  create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_pass_claim_registration (registration_id),
  KEY idx_platform_pass_claim_membership (membership_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通行证免费领取的自玩报名（¥0 行）';

-- 套餐默认未上架（status=0）；价格按已批准原型（年卡 ¥299 / 月卡 ¥39），上架与改价由运营在库内确认后执行。
INSERT IGNORE INTO platform_pass_plan (plan_code, title, price_fen, valid_days, status, sort)
VALUES ('YEAR', '年卡', 29900, 365, 0, 1), ('MONTH', '月卡', 3900, 30, 0, 2);
