-- PROPOSAL ONLY. No DDL has been executed. Integrator approval and isolated MySQL preflight required.
-- All four gateway preorder routes freeze this record BEFORE external SDK submission.
-- Preserve old rows forever independently of payParams/response TTL. Never overwrite request_hash or out_trade_no.
CREATE TABLE wx_pay_order_intent (
    mchid VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    out_trade_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_out_trade_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    biz_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    biz_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    appid VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trade_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    total_fen BIGINT NOT NULL,
    payer_openid VARCHAR(128) NOT NULL,
    request_description VARCHAR(128) NOT NULL,
    notify_url VARCHAR(1024) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_confirmed_at DATETIME(6) NULL,
    active_biz_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN closed_confirmed_at IS NULL THEN biz_id ELSE NULL END) STORED,
    PRIMARY KEY (mchid, out_trade_no),
    UNIQUE KEY uk_wx_pay_intent_active (biz_type, active_biz_id),
    UNIQUE KEY uk_wx_pay_intent_attempt (biz_type, biz_id, attempt_no),
    KEY ix_wx_pay_intent_current (mchid, business_out_trade_no, attempt_no),
    KEY ix_wx_pay_intent_business (biz_type, biz_id, created_at),
    CONSTRAINT ck_wx_pay_intent_fen CHECK (total_fen > 0),
    CONSTRAINT ck_wx_pay_intent_channel CHECK (trade_type IN ('APP','JSAPI') AND currency='CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Preflight: SHOW CREATE TABLE for this table if present; compare exact schema, do not blindly IF NOT EXISTS.
-- Verify existing business out-trade numbers / frozen amount / case-sensitive uniqueness in an approved isolated copy.
-- Historical accepted WeChat orders remain authoritative read-only evidence; never auto-submit a fabricated backfill.
-- Confirm MySQL 8 generated-column unique-key behavior, independent transaction commits and concurrent channel conflict.
-- External calls from MerchantCommerceService.createOrder must move OUTSIDE its business transaction before enablement.
-- To reopen the SAME business, the gateway derives a fresh deterministic external alias AFTER original query confirms CLOSED.
-- Domain business order numbers remain unchanged; callbacks/queries/refunds retain the actual external number separately.
-- This table retains the old external number and request hash; UNKNOWN / timeout / NOT_FOUND never closes the intent.
-- Rollback: stop new submissions; retain this table and all rows. No DROP, DELETE, identity reuse or refund inference.
