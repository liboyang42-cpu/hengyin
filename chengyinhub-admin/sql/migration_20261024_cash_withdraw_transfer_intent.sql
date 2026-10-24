-- REVIEW CANDIDATE ONLY. No production DDL executed by task A.
-- Apply only after the original fund-fact migration; preserve all original rows and append-only triggers.
-- NULL request_version remains historical/unprovable. Never backfill merchant/app/hash from current config.
ALTER TABLE member_cash_withdraw_fund_fact
    ADD COLUMN request_mchid VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_appid VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_amount_fen BIGINT NULL,
    ADD COLUMN request_openid VARCHAR(128) COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_remark VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_version INT NULL,
    ADD COLUMN request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN channel_batch_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT ck_cash_fund_request CHECK (request_version IS NULL OR
        (request_version = 1 AND request_mchid IS NOT NULL AND request_appid IS NOT NULL
         AND request_openid IS NOT NULL AND request_remark IS NOT NULL AND request_hash IS NOT NULL
         AND request_amount_fen IS NOT NULL AND request_amount_fen > 0 AND request_amount_fen = amount * 100));
