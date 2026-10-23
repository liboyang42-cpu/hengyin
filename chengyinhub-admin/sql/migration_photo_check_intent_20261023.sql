-- E Photo intent canonical candidate. Never execute on production as part of this change.
-- Preflight: MySQL 8+, absent photo_check_intent, C play_attempt_log.request_fingerprint
-- migration reviewed/applied before application activation. Existing-table drift must block,
-- not be hidden by IF NOT EXISTS. No old records, quota sources or jobs are changed.
CREATE TABLE photo_check_intent (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    action_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    context_json JSON NOT NULL,
    image_reference VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_day CHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nonce CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    encoded_input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    model_name VARCHAR(255) NULL,
    prompt_version VARCHAR(255) NULL,
    encoder_version VARCHAR(255) NULL,
    result_pass TINYINT NULL,
    result_confidence INT NULL,
    result_reason TEXT NULL,
    result_degraded TINYINT NULL,
    attempt_log_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_photo_command (member_id, session_id, action_key),
    UNIQUE KEY uk_photo_nonce (nonce),
    UNIQUE KEY uk_photo_attempt (attempt_log_id),
    CONSTRAINT ck_photo_state CHECK (state IN ('PREPARED','IN_FLIGHT','UNKNOWN','RESULT_READY','APPLIED','NOT_SENT')),
    CONSTRAINT ck_photo_provider CHECK (provider_state IS NULL OR provider_state IN ('NOT_SENT','UNKNOWN','RECEIVED')),
    CONSTRAINT ck_photo_confidence CHECK (result_confidence IS NULL OR result_confidence BETWEEN 0 AND 100),
    CONSTRAINT ck_photo_degraded CHECK (result_degraded IS NULL OR result_degraded=0 OR
        (result_degraded=1 AND result_pass=0 AND result_confidence=0)),
    CONSTRAINT ck_photo_result CHECK (state NOT IN ('RESULT_READY','APPLIED') OR
        (result_pass IS NOT NULL AND result_confidence IS NOT NULL AND result_reason IS NOT NULL AND result_degraded IS NOT NULL)),
    CONSTRAINT ck_photo_applied CHECK ((state='APPLIED' AND attempt_log_id IS NOT NULL) OR (state<>'APPLIED' AND attempt_log_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
-- Readback: SHOW CREATE TABLE photo_check_intent; compare all unique indexes and columns.
-- Rollback: disable entry points; retain table and original command/result identities.
-- Never DROP/TRUNCATE active intents or reuse their nonce/key when reverting application code.
