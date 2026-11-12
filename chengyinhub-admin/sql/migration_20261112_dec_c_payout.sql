-- DEC-C payout: personal club receiver consent, immutable beneficiary and typed identity.
-- NEW switch payout.ecommerce.personal-club-enabled remains default OFF in code.
-- No historical OpenID/consent/beneficiary backfill. Apply before enabling the new jar feature.
CREATE TABLE IF NOT EXISTS ecom_personal_receiver (
 member_id BIGINT NOT NULL,
 appid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 openid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL,
 consented_at DATETIME(3) NOT NULL,
 verified_at DATETIME(3) NOT NULL,
 PRIMARY KEY(member_id, appid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS club_personal_payout_beneficiary (
 scope_type VARCHAR(16) NOT NULL,
 scope_id BIGINT NOT NULL,
 club_id BIGINT NOT NULL,
 club_name VARCHAR(255) NOT NULL,
 member_id BIGINT NOT NULL,
 frozen_at DATETIME NOT NULL,
 payout_ready_at DATETIME(3) NULL,
 PRIMARY KEY(scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='club_personal_payout_beneficiary' AND column_name='payout_ready_at')=0, 'ALTER TABLE club_personal_payout_beneficiary ADD COLUMN payout_ready_at DATETIME(3) NULL', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coop_settlement' AND column_name='club_id')=0, 'ALTER TABLE coop_settlement ADD COLUMN club_id BIGINT NULL', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coop_settlement' AND column_name='club_name')=0, 'ALTER TABLE coop_settlement ADD COLUMN club_name VARCHAR(255) NULL', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coop_settlement' AND column_name='payout_hold_reason')=0, 'ALTER TABLE coop_settlement ADD COLUMN payout_hold_reason VARCHAR(128) NULL', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coop_settlement_original_component' AND column_name='club_id')=0, 'ALTER TABLE coop_settlement_original_component ADD COLUMN club_id BIGINT NULL', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coop_settlement_original_component' AND column_name='club_name')=0, 'ALTER TABLE coop_settlement_original_component ADD COLUMN club_name VARCHAR(255) NULL', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='settlement_detail' AND column_name='club_id')=0, 'ALTER TABLE settlement_detail ADD COLUMN club_id BIGINT NULL', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='settlement_detail' AND column_name='club_name')=0, 'ALTER TABLE settlement_detail ADD COLUMN club_name VARCHAR(255) NULL', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ecom_profit_share_line' AND column_name='receiver_type')=0, 'ALTER TABLE ecom_profit_share_line ADD COLUMN receiver_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''MERCHANT_ID''', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ecom_profit_share_line' AND column_name='receiver_appid')=0, 'ALTER TABLE ecom_profit_share_line ADD COLUMN receiver_appid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''''', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ecom_profit_share_line' AND column_name='club_id')=0, 'ALTER TABLE ecom_profit_share_line ADD COLUMN club_id BIGINT NULL', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ecom_profit_share_line' AND column_name='club_name')=0, 'ALTER TABLE ecom_profit_share_line ADD COLUMN club_name VARCHAR(255) NULL', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ecom_profit_share_return' AND column_name='return_type')=0, 'ALTER TABLE ecom_profit_share_return ADD COLUMN return_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''MERCHANT_ID''', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ecom_profit_share_return' AND column_name='return_appid')=0, 'ALTER TABLE ecom_profit_share_return ADD COLUMN return_appid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''''', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

-- Account columns must fit OpenIDs; existing merchant ids retain exact bytes.
ALTER TABLE ecom_profit_share_line MODIFY COLUMN receiver_mchid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE ecom_profit_share_return MODIFY COLUMN return_mchid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
-- Replace merchant-only uniqueness with full typed identity; supports repeat-safe migration.
-- FK fk_ecom_share_return_order 依赖旧唯一键的最左列：必须先建新键，再删旧键（否则 1553）。
SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='ecom_profit_share_return' AND index_name='uk_ecom_share_return_identity')=0,
 'ALTER TABLE ecom_profit_share_return ADD UNIQUE KEY uk_ecom_share_return_identity (share_order_id,out_refund_no,return_type,return_appid,return_mchid)', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;
SET @dec_payout_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='ecom_profit_share_return' AND index_name='uk_ecom_share_return_refund')>0,
 'ALTER TABLE ecom_profit_share_return DROP INDEX uk_ecom_share_return_refund', 'SELECT 1');
PREPARE dec_payout_stmt FROM @dec_payout_ddl;
EXECUTE dec_payout_stmt;
DEALLOCATE PREPARE dec_payout_stmt;

