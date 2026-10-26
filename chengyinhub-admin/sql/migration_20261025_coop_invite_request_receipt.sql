-- Dedicated original-request recovery, not a pending-invite uniqueness rule. No historical backfill.
CREATE TABLE IF NOT EXISTS coop_invite_request_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    principal_member_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint_version INT NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_type VARCHAR(16) NOT NULL,
    from_id BIGINT NOT NULL,
    from_owner_member_id BIGINT NOT NULL,
    to_owner_member_id BIGINT NOT NULL,
    scope VARCHAR(16) NOT NULL,
    invite_type INT NOT NULL,
    topic_id BIGINT NOT NULL,
    to_type VARCHAR(16) NOT NULL,
    to_id BIGINT NOT NULL,
    invite_id BIGINT NULL COMMENT 'Bound before the creating transaction commits',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coop_request_principal (principal_member_id, request_id),
    UNIQUE KEY uk_coop_request_invite (invite_id),
    KEY idx_coop_request_page (principal_member_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
