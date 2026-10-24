-- SOURCE-ONLY CANDIDATE. No production DDL/backfill is authorized or executed here.
-- Prerequisites: Oracle MySQL8/InnoDB, original cms_registration (signed BIGINT id),
-- canonical B wx_verified_payment_fact (unsigned BIGINT id), strict mode and FK checks enabled.
-- Preflight exact columns/indexes/FKs/CHECKs and all table/constraint/trigger names; refuse collisions.
-- This records local successful consumption, not provider success, a second inbox or a lease.
CREATE TABLE cms_registration_payment_fact (
    registration_id BIGINT NOT NULL,
    registration_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_fact_id BIGINT UNSIGNED NOT NULL,
    payment_fact_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (registration_id),
    UNIQUE KEY uk_registration_payment_fact (payment_fact_id),
    CONSTRAINT fk_registration_paid_fact_registration FOREIGN KEY (registration_id) REFERENCES cms_registration(id),
    CONSTRAINT fk_registration_paid_fact_original FOREIGN KEY (payment_fact_id) REFERENCES wx_verified_payment_fact(id),
    CONSTRAINT ck_registration_paid_fact_identity CHECK (registration_id > 0 AND payment_fact_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Single-statement triggers; compatible with both ScriptUtils and the existing controlled SQL parser.
CREATE TRIGGER registration_paid_fact_no_update BEFORE UPDATE ON cms_registration_payment_fact
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'registration payment facts are append only';
CREATE TRIGGER registration_paid_fact_no_delete BEFORE DELETE ON cms_registration_payment_fact
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'registration payment facts are append only';

-- No historical inference or backfill. Domain owner appends only in the successful business transaction.
-- Controlled acceptance must cover exact/ref-conflicting replay, rollback after local effects, current reads,
-- binary identity, one original payer per fact, both immutable triggers and original FK retention.
-- Production rollback: stop new inline consumers and coordinate dependent readers; retain this table,
-- its unique identities, original provider facts, receipts and funds. Never DROP/DELETE or release by TTL.
