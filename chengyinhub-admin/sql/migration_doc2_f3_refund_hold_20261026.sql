-- F3 fulfillment freeze references refund_application; no payment intent, no backfill, no feature enablement.
CREATE TABLE IF NOT EXISTS explore_bundle_refund_hold (
 registration_id BIGINT NOT NULL PRIMARY KEY,
 holder_member_id BIGINT NOT NULL,
 source_registration_id BIGINT NOT NULL,
 refund_id BIGINT NULL,
 refund_no VARCHAR(64) NULL,
 cash_amount DECIMAL(18,2) NOT NULL,
 state VARCHAR(40) NOT NULL,
 version INT NOT NULL DEFAULT 0,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_f3_refund_intent (refund_id),
 CONSTRAINT ck_f3_refund_cash CHECK (cash_amount >= 0),
 CONSTRAINT ck_f3_refund_identity CHECK ((refund_id IS NULL AND refund_no IS NULL) OR (refund_id IS NOT NULL AND refund_no IS NOT NULL)),
 CONSTRAINT ck_f3_refund_state CHECK (state IN ('PREPARED','BOUND','APPROVED','SUCCEEDED','ZERO_CASH_COMPLETED','REJECTED_HELD','MANUAL_REVIEW_HELD','RECONCILIATION_REQUIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SELECT COUNT(*) = 10 FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='explore_bundle_refund_hold';
