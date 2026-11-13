-- DEC-C D-19. Schema only: does not enable checkout, seed prices, or dispatch payments.
-- Existing manual/legacy grants remain intact and unlinked; never guess their order attribution.
SET @dec_c_perks_column = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='merchant_subscription' AND column_name='source_order_id');
SET @dec_c_perks_sql = IF(@dec_c_perks_column=0,
 'ALTER TABLE merchant_subscription ADD COLUMN source_order_id BIGINT NULL COMMENT ''Immutable paid commerce order contribution''', 'SELECT 1');
PREPARE dec_c_perks_stmt FROM @dec_c_perks_sql;
EXECUTE dec_c_perks_stmt;
DEALLOCATE PREPARE dec_c_perks_stmt;
SET @dec_c_perks_index = (SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='merchant_subscription' AND index_name='uk_subscription_source_order');
SET @dec_c_perks_sql = IF(@dec_c_perks_index=0,
 'ALTER TABLE merchant_subscription ADD UNIQUE KEY uk_subscription_source_order(source_order_id)', 'SELECT 1');
PREPARE dec_c_perks_stmt FROM @dec_c_perks_sql;
EXECUTE dec_c_perks_stmt;
DEALLOCATE PREPARE dec_c_perks_stmt;
CREATE TABLE IF NOT EXISTS merchant_commerce_refund (
 order_id BIGINT NOT NULL,
 refund_no VARCHAR(32) NOT NULL,
 refund_fact_id BIGINT NULL,
 refund_fact_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 create_time DATETIME NOT NULL,
 settled_time DATETIME NULL,
 PRIMARY KEY(order_id),
 UNIQUE KEY uk_commerce_refund_no(refund_no),
 UNIQUE KEY uk_commerce_refund_fact(refund_fact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Original full paid-perk refund intent and verified settlement';
