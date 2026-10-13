-- DEC-01/DEC-02 资金动作前置 challenge；本文件仅为迁移草案，未执行。
-- 执行前必须走 chengyin-db-migrate 只读预检并由总控确认。

-- 重复/半迁移预检：必须返回 0 行；有结果即停止，不得继续 CREATE。
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'fund_action_preflight';

CREATE TABLE fund_action_preflight (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    counterparty_key_hash CHAR(64) NULL,
    challenge_nonce VARCHAR(64) NOT NULL,
    challenge_token_hash CHAR(64) NOT NULL,
    state TINYINT NOT NULL DEFAULT 0,
    amount DECIMAL(14,2) NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    display_name VARCHAR(128) NULL,
    display_account_mask VARCHAR(64) NULL,
    display_phone_mask VARCHAR(32) NULL,
    context_initiated_at DATETIME(3) NOT NULL,
    context_where VARCHAR(128) NULL,
    context_device VARCHAR(64) NULL,
    risk_level VARCHAR(16) NOT NULL DEFAULT 'STATIC',
    risk_reason_codes VARCHAR(500) NULL,
    expires_at DATETIME(3) NOT NULL,
    confirmed_at DATETIME(3) NULL,
    rejected_at DATETIME(3) NULL,
    consumed_at DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_preflight_intent (member_id, action_type, request_id),
    UNIQUE KEY uk_fund_preflight_token (challenge_token_hash),
    KEY idx_fund_preflight_counterparty (member_id, action_type, counterparty_key_hash, state),
    KEY idx_fund_preflight_expiry (state, expires_at),
    CONSTRAINT chk_fund_preflight_state CHECK (state IN (0,1,2,3)),
    CONSTRAINT chk_fund_preflight_risk CHECK (risk_level IN ('STATIC','WARNING','BLOCKED')),
    CONSTRAINT chk_fund_preflight_amount CHECK (amount IS NULL OR amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金动作二次确认 challenge';

-- 执行后回读：列非空、唯一键列序、CHECK 与 InnoDB 必须与上面定义一致。
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'fund_action_preflight'
ORDER BY ordinal_position;

SELECT index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'fund_action_preflight'
GROUP BY index_name, non_unique
ORDER BY index_name;

SELECT engine
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'fund_action_preflight';
