-- SOURCE-ONLY PROPOSAL. No DDL is authorized/executed by this candidate.
-- Preflight: MySQL8/InnoDB; SHOW CREATE TABLE wx_payment_callback_inbox and the three new names;
-- verify original inbox migrations, case-sensitive provider identifiers and no name/schema collision.
-- No historical row is guessed, upgraded, deleted or marked successful by this migration.
CREATE TABLE wx_verified_payment_fact (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    fact_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mchid VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    appid VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_out_trade_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    wx_out_trade_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_event_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trade_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    total_fen BIGINT NOT NULL,
    amount_fen BIGINT NOT NULL,
    success_at VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    intent_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    fact_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verification_version INT NOT NULL,
    verification_outcome VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verification_method VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_payment_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wx_fact_provider (fact_kind,mchid,provider_id),
    UNIQUE KEY uk_wx_fact_merchant_event (fact_kind,mchid,merchant_event_no),
    KEY ix_wx_fact_order (mchid,wx_out_trade_no),
    CONSTRAINT ck_wx_fact_kind CHECK (fact_kind IN ('PAYMENT','REFUND')),
    CONSTRAINT ck_wx_fact_money CHECK (total_fen > 0 AND amount_fen > 0 AND amount_fen <= total_fen),
    CONSTRAINT ck_wx_fact_verified CHECK (verification_outcome='VERIFIED' AND verification_version=1
        AND verification_method='SDK_QUERY_ORIGINAL_ORDER' AND currency='CNY'
        AND trade_type IN ('APP','JSAPI')),
    CONSTRAINT ck_wx_fact_payment CHECK (fact_kind<>'PAYMENT' OR
        (merchant_event_no=wx_out_trade_no AND provider_id=transaction_id AND amount_fen=total_fen
         AND original_payment_state='SUCCESS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wx_verified_payment_fact_rejection (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rejection_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rejection_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_fact_id BIGINT UNSIGNED NULL,
    candidate_fact_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    notify_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    candidate_facts TEXT NOT NULL COMMENT 'Canonical business/provider facts only; no raw notification, payer or credentials',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wx_fact_rejection (rejection_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Sidecar reuses the existing inbox identity without changing any old inbox column or migration.
CREATE TABLE wx_payment_callback_fact_link (
    inbox_id BIGINT UNSIGNED NOT NULL,
    notify_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fact_id BIGINT UNSIGNED NOT NULL,
    fact_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (inbox_id),
    UNIQUE KEY uk_wx_callback_fact_notify (notify_hash),
    UNIQUE KEY uk_wx_callback_fact_identity (fact_id),
    CONSTRAINT fk_wx_callback_fact_inbox FOREIGN KEY (inbox_id) REFERENCES wx_payment_callback_inbox(id),
    CONSTRAINT fk_wx_callback_verified_fact FOREIGN KEY (fact_id) REFERENCES wx_verified_payment_fact(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- Controlled preflight must prove both unique identities, rollback, concurrent replay/conflict,
-- durable rejection after a rejected registration, and no business mutation on UNKNOWN.
-- Integrator owns registry/order/approved isolated execution. Existing inbox DDL must run first.
-- Rollback: stop new verified consumers; keep all three tables/identities/evidence. No DROP/DELETE,
-- TTL expiry, success inference, automatic backfill or reopening of an UNKNOWN business identity.
