-- DRAFT ONLY. NOT EXECUTED, NOT DEPLOYMENT-READY. No application wiring or backfill.
-- Requires approved same-datasource transaction wiring and a committed-source authority
-- which retains current authorization locks through commit. No such production port is supplied.
-- Requires independent MySQL schema/version/engine/privilege review; H2 is not that review.
-- Snapshot writer privilege should be INSERT/SELECT only; no UPDATE/DELETE path is proposed.
CREATE TABLE sms_coupon_terms_snapshot (
    history_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    issuer_member_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    actor_member_id BIGINT NOT NULL,
    beneficiary_member_id BIGINT NOT NULL,
    nonce VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_receipt_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    intent_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    terms_json TEXT NOT NULL,
    terms_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at BIGINT NOT NULL,
    PRIMARY KEY (history_id),
    UNIQUE KEY uk_coupon_terms_issuer_nonce (issuer_member_id, nonce),
    UNIQUE KEY uk_coupon_terms_source_owner_coupon (source_receipt_id, beneficiary_member_id, coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Intentionally no INSERT...SELECT backfill, parent-terms fallback, UPDATE, or activation SQL.
