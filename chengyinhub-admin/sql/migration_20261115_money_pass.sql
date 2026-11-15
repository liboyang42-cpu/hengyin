-- 2026-11-15 R6a money-pass:通行证 iOS 内购(StoreKit 2 + App Store Server API + Server Notifications V2)。
-- 只新增两张表,并把 platform_pass_order.channel 的 CHECK 放宽到 ('JSAPI','XPAY','APPLE');不删列、不改数据。可重复执行。
-- 前置:migration_20261110_platform_pass.sql(建 platform_pass_order 与 CHECK)、migration_20261113_platform_pass_hardening.sql。
-- 回滚(仅在没有 APPLE 渠道订单时):
--   DROP TABLE IF EXISTS platform_pass_apple_notification;
--   DROP TABLE IF EXISTS platform_pass_apple_order;
--   ALTER TABLE platform_pass_order DROP CHECK ck_platform_pass_order_channel;
--   ALTER TABLE platform_pass_order ADD CONSTRAINT ck_platform_pass_order_channel CHECK (channel IN ('JSAPI', 'XPAY'));
-- 生产执行必须走 chengyin-db-migrate;本迁移不打开任何开关,Apple 渠道仍由配置与资金开关关闭。

CREATE TABLE IF NOT EXISTS platform_pass_apple_order (
  order_no varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'platform_pass_order.order_no(channel=APPLE)',
  member_id bigint NOT NULL,
  plan_code varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  product_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '建单时冻结的 App Store 商品 ID',
  app_account_token char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端签发,Apple 签进交易,用来对单',
  bundle_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  environment varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Sandbox / Production',
  transaction_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Apple transactionId,一笔交易只开通一单',
  original_transaction_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  price_milliunits bigint NULL COMMENT 'Apple 交易价格(千分单位),只留痕',
  currency char(3) CHARACTER SET ascii COLLATE ascii_bin NULL,
  purchase_date datetime(3) NULL,
  state varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / GRANTED / REVOKED / REVIEW',
  revoked_at datetime(3) NULL,
  revocation_reason int NULL,
  create_time datetime(3) NOT NULL,
  update_time datetime(3) NOT NULL,
  PRIMARY KEY (order_no),
  UNIQUE KEY uk_pp_apple_token (app_account_token),
  UNIQUE KEY uk_pp_apple_transaction (transaction_id),
  KEY idx_pp_apple_member (member_id),
  CONSTRAINT ck_pp_apple_state CHECK (state IN ('PENDING', 'GRANTED', 'REVOKED', 'REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='R6a 通行证 App Store 购买';

CREATE TABLE IF NOT EXISTS platform_pass_apple_notification (
  notification_uuid char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  notification_type varchar(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  subtype varchar(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  transaction_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payload_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED / PENDING / APPLIED / IGNORED',
  reason_code varchar(64) NULL,
  create_time datetime(3) NOT NULL,
  update_time datetime(3) NOT NULL,
  PRIMARY KEY (notification_uuid),
  KEY idx_pp_apple_notification_txn (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='R6a App Store Server Notifications V2 收件箱(通行证)';

SET @pp_apple_has_check = (SELECT COUNT(*) FROM information_schema.check_constraints
    WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_platform_pass_order_channel');
SET @pp_apple_has_apple = (SELECT COUNT(*) FROM information_schema.check_constraints
    WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_platform_pass_order_channel'
      AND check_clause LIKE '%APPLE%');
SET @pp_apple_ddl_0 = IF(@pp_apple_has_check = 1 AND @pp_apple_has_apple = 0,
    'ALTER TABLE platform_pass_order DROP CHECK ck_platform_pass_order_channel', 'SELECT 1');
PREPARE pp_apple_stmt_0 FROM @pp_apple_ddl_0;
EXECUTE pp_apple_stmt_0;
DEALLOCATE PREPARE pp_apple_stmt_0;
SET @pp_apple_ddl_1 = IF((SELECT COUNT(*) FROM information_schema.check_constraints
    WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_platform_pass_order_channel') = 0,
    'ALTER TABLE platform_pass_order ADD CONSTRAINT ck_platform_pass_order_channel CHECK (channel IN (''JSAPI'', ''XPAY'', ''APPLE''))', 'SELECT 1');
PREPARE pp_apple_stmt_1 FROM @pp_apple_ddl_1;
EXECUTE pp_apple_stmt_1;
DEALLOCATE PREPARE pp_apple_stmt_1;
