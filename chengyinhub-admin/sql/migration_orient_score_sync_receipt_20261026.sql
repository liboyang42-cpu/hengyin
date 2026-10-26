-- W8-06: keyed synchronization result. Apply before deploying the keyed sync consumer.
-- No ledger mutation or reward policy change. Retain receipts on code rollback.
CREATE TABLE IF NOT EXISTS orient_score_sync_receipt (
    session_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    idempotency_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    added INT NOT NULL,
    total_centi BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
