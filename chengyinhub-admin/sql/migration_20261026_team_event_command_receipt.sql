-- W8-07 original-request receipts only. No legacy backfill; no deployment/config activation.
CREATE TABLE IF NOT EXISTS team_event_command_receipt (
    actor_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    domain VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id BIGINT NOT NULL,
    action VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_json TEXT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_id, request_id),
    KEY idx_team_event_command_target (domain, target_id, actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
