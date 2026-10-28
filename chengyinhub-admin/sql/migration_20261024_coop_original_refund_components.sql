-- SOURCE CANDIDATE ONLY. NO PRODUCTION DDL EXECUTED.
-- Requires reviewed migration_20261023_g_cash_evidence_expand.sql and B migration_20261024_wx_verified_payment_fact.sql, MySQL8/InnoDB.
-- Preserve legacy claim_id NOT NULL and all old unique keys. No manufactured claim/member.
-- Preflight precedes first CREATE. DDL auto-commits: no generic rollback is safe.
-- This candidate is NOT approved for application. CI-only exact-prefix recovery source is in Wp04OriginalRefundMySqlIT; all MySQL validation remains NOT_RUN.
-- runner: assert=scalar-true
SELECT CAST(IF(
 @@foreign_key_checks=1
 AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@sql_mode)>0)
 AND CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)=8
 AND (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)>0
      OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=16)
 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
   AND table_name IN ('coop_settlement_original_component','coop_settlement_refund_receipt','coop_settlement_original_adjustment'))=0
 AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
   AND constraint_name IN ('fk_goc_close','fk_goc_source','fk_goc_payer','fk_goc_head','fk_goc_member','fk_goc_payment_fact','ck_goc_payload',
     'fk_gor_close','fk_gor_source','fk_gor_payer','fk_gor_refund','fk_gor_wx_fact','ck_gor_payload',
     'fk_goa_component','fk_goa_receipt','fk_goa_head','ck_goa_payload'))=0
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='coop_settlement_adjustment' AND column_name='claim_id' AND is_nullable='NO')=1
 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
   AND table_name IN ('wx_verified_payment_fact','wx_payment_callback_fact_link'))=2
 AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics WHERE table_schema=DATABASE()
   AND table_name='coop_settlement' AND index_name='uk_g_cash_head_id_close' AND non_unique=0)='id,cash_close_id'
 AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics WHERE table_schema=DATABASE()
   AND table_name='coop_settlement_cash_contribution' AND index_name='uk_g_cash_contribution_close_registration' AND non_unique=0)='cash_close_id,registration_id'
 AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics WHERE table_schema=DATABASE()
   AND table_name='coop_settlement_cash_close' AND index_name='uk_g_cash_close_id_topic' AND non_unique=0)='id,topic_id',
 1,JSON_EXTRACT('ASSERT_FAILED__original_refund_preflight','$')) AS UNSIGNED) AS assertion_ok;

CREATE TABLE coop_settlement_original_component (
 id BIGINT NOT NULL AUTO_INCREMENT,
 cash_close_id BIGINT NOT NULL,
 topic_id BIGINT NOT NULL,
 source_registration_id BIGINT NOT NULL,
 payer_registration_id BIGINT NOT NULL,
 settlement_id BIGINT NOT NULL,
 source_claim_id BIGINT NULL,
 payment_fact_id BIGINT UNSIGNED NOT NULL,
 payment_fact_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 receiver_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 receiver_member_id BIGINT UNSIGNED NULL,
 line_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 original_amount DECIMAL(15,2) NOT NULL,
 original_order_cash DECIMAL(15,2) NOT NULL,
 calculation_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id),
 UNIQUE KEY uk_goc_origin(cash_close_id,source_registration_id,settlement_id),
 UNIQUE KEY uk_goc_id_close_payer_head(id,cash_close_id,payer_registration_id,settlement_id),
 KEY idx_goc_payer(cash_close_id,payer_registration_id),
 CONSTRAINT fk_goc_close FOREIGN KEY(cash_close_id,topic_id) REFERENCES coop_settlement_cash_close(id,topic_id),
 CONSTRAINT fk_goc_source FOREIGN KEY(cash_close_id,source_registration_id) REFERENCES coop_settlement_cash_contribution(cash_close_id,registration_id),
 CONSTRAINT fk_goc_payer FOREIGN KEY(cash_close_id,payer_registration_id) REFERENCES coop_settlement_cash_contribution(cash_close_id,registration_id),
 CONSTRAINT fk_goc_head FOREIGN KEY(settlement_id,cash_close_id) REFERENCES coop_settlement(id,cash_close_id),
 CONSTRAINT fk_goc_member FOREIGN KEY(receiver_member_id) REFERENCES ums_member(id),
 CONSTRAINT fk_goc_payment_fact FOREIGN KEY(payment_fact_id) REFERENCES wx_verified_payment_fact(id),
 CONSTRAINT ck_goc_payload CHECK (CASE WHEN original_order_cash>0
   AND calculation_version<>'' AND source_fingerprint REGEXP '^[0-9a-f]{64}$' AND payment_fact_hash REGEXP '^[0-9a-f]{64}$'
   AND (original_amount>=0 OR BINARY line_type=BINARY 'INITIATOR_MARGIN')
   AND ((BINARY receiver_kind=BINARY 'ACCOUNTING' AND receiver_member_id IS NULL AND source_claim_id IS NULL
         AND BINARY line_type=BINARY 'PLATFORM')
     OR (BINARY receiver_kind=BINARY 'MEMBER' AND receiver_member_id>0
         AND ((BINARY line_type IN (BINARY 'MERCHANT_VERIFY',BINARY 'LEAD_FEE') AND source_claim_id>0)
           OR (BINARY line_type IN (BINARY 'INITIATOR_GUARANTEE',BINARY 'INITIATOR_MARGIN') AND source_claim_id IS NULL))))
   THEN 1 ELSE 0 END=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE coop_settlement_refund_receipt (
 id BIGINT NOT NULL AUTO_INCREMENT,
 cash_close_id BIGINT NOT NULL,
 topic_id BIGINT NOT NULL,
 refund_id BIGINT NOT NULL,
 verified_fact_id BIGINT NOT NULL,
 verified_fact_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 verified_fact_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 wx_fact_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN BINARY verified_fact_kind=BINARY 'WX' THEN verified_fact_id ELSE NULL END) STORED,
 source_registration_id BIGINT NOT NULL,
 payer_registration_id BIGINT NOT NULL,
 currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 refund_amount DECIMAL(15,2) NOT NULL,
 total_amount DECIMAL(15,2) NOT NULL,
 cumulative_before DECIMAL(15,2) NOT NULL,
 cumulative_after DECIMAL(15,2) NOT NULL,
 reversed_amount DECIMAL(15,2) NOT NULL,
 calculation_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id),
 UNIQUE KEY uk_gor_refund(refund_id),
 UNIQUE KEY uk_gor_fact(verified_fact_kind,verified_fact_id),
 UNIQUE KEY uk_gor_id_close_payer(id,cash_close_id,payer_registration_id),
 KEY idx_gor_payer(cash_close_id,payer_registration_id),
 CONSTRAINT fk_gor_close FOREIGN KEY(cash_close_id,topic_id) REFERENCES coop_settlement_cash_close(id,topic_id),
 CONSTRAINT fk_gor_source FOREIGN KEY(cash_close_id,source_registration_id) REFERENCES coop_settlement_cash_contribution(cash_close_id,registration_id),
 CONSTRAINT fk_gor_payer FOREIGN KEY(cash_close_id,payer_registration_id) REFERENCES coop_settlement_cash_contribution(cash_close_id,registration_id),
 CONSTRAINT fk_gor_refund FOREIGN KEY(refund_id) REFERENCES refund_application(id),
 CONSTRAINT fk_gor_wx_fact FOREIGN KEY(wx_fact_id) REFERENCES wx_verified_payment_fact(id),
 CONSTRAINT ck_gor_payload CHECK (CASE WHEN refund_id>0 AND verified_fact_id>0
   AND BINARY verified_fact_kind IN (BINARY 'WX',BINARY 'MANUAL') AND BINARY currency=BINARY 'CNY'
   AND refund_amount>0 AND total_amount>0 AND cumulative_before>=0
   AND cumulative_after=cumulative_before+refund_amount AND cumulative_after<=total_amount
   AND reversed_amount=refund_amount AND calculation_version<>'' AND source_fingerprint REGEXP '^[0-9a-f]{64}$'
   AND verified_fact_hash REGEXP '^[0-9a-f]{64}$'
   AND (BINARY verified_fact_kind=BINARY 'WX' OR verified_fact_id=refund_id)
   THEN 1 ELSE 0 END=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE coop_settlement_original_adjustment (
 id BIGINT NOT NULL AUTO_INCREMENT,
 cash_close_id BIGINT NOT NULL,
 component_id BIGINT NOT NULL,
 receipt_id BIGINT NOT NULL,
 settlement_id BIGINT NOT NULL,
 payer_registration_id BIGINT NOT NULL,
 amount DECIMAL(15,2) NOT NULL,
 head_was_paid TINYINT NOT NULL,
 wallet_debit DECIMAL(15,2) NOT NULL,
 debt_amount DECIMAL(15,2) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id),
 UNIQUE KEY uk_goa_event(component_id,receipt_id),
 KEY idx_goa_receipt(receipt_id,cash_close_id,payer_registration_id),
 KEY idx_goa_head(settlement_id,cash_close_id),
 CONSTRAINT fk_goa_component FOREIGN KEY(component_id,cash_close_id,payer_registration_id,settlement_id)
   REFERENCES coop_settlement_original_component(id,cash_close_id,payer_registration_id,settlement_id),
 CONSTRAINT fk_goa_receipt FOREIGN KEY(receipt_id,cash_close_id,payer_registration_id)
   REFERENCES coop_settlement_refund_receipt(id,cash_close_id,payer_registration_id),
 CONSTRAINT fk_goa_head FOREIGN KEY(settlement_id,cash_close_id) REFERENCES coop_settlement(id,cash_close_id),
 CONSTRAINT ck_goa_payload CHECK (CASE WHEN amount<0 AND head_was_paid IN (0,1)
   AND wallet_debit>=0 AND debt_amount>=0
   AND ((head_was_paid=0 AND wallet_debit=0 AND debt_amount=0)
     OR (head_was_paid=1 AND amount<0 AND wallet_debit+debt_amount=-amount))
   THEN 1 ELSE 0 END=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- No legacy backfill: aggregate-only old heads cannot reconstruct an order's original components.
-- Rollback: stop new producers/consumers; keep nonempty tables/receipts and original claims forever.
-- Empty interrupted stages may be removed only after exact shape/emptiness validation, in order:
-- original_adjustment -> refund_receipt -> original_component. Never delete money or overwrite old indexes.
