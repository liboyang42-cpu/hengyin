-- Immutable original debit/transfer identity. No historical identity is inferred or backfilled.
-- Run through the migration ledger once; a direct rerun must fail instead of accepting a different table.
CREATE TABLE member_cash_withdraw_transfer_intent (
    withdraw_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    mchid VARCHAR(32) NOT NULL,
    appid VARCHAR(64) NOT NULL,
    out_batch_no VARCHAR(32) NOT NULL,
    out_detail_no VARCHAR(32) NOT NULL,
    amount_fen BIGINT NOT NULL,
    openid VARCHAR(128) NOT NULL,
    remark VARCHAR(128) NOT NULL,
    request_version INT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    balance_before DECIMAL(18,2) NOT NULL,
    balance_after DECIMAL(18,2) NOT NULL,
    debit_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    submit_time DATETIME(3) NULL,
    local_rejection_time DATETIME(3) NULL,
    PRIMARY KEY (withdraw_id),
    UNIQUE KEY uk_cash_transfer_batch (mchid, out_batch_no),
    CONSTRAINT chk_cash_intent_amount CHECK (amount_fen > 0 AND balance_after >= 0
        AND (balance_before - balance_after) * 100 = amount_fen),
    CONSTRAINT chk_cash_intent_version CHECK (request_version = 1),
    CONSTRAINT chk_cash_intent_rejection CHECK (local_rejection_time IS NULL OR submit_time IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
