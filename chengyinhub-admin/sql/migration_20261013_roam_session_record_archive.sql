-- Test migration only. Archives preserve client claims, never fog, XP, rewards or settlement.
CREATE TABLE IF NOT EXISTS roam_session_record_archive (
    member_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    tile CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    collected_at DATETIME(3) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    evidence_type VARCHAR(24) NOT NULL COMMENT 'CLIENT_REPORTED: no authoritative acquisition proof',
    PRIMARY KEY (session_id, tile),
    KEY idx_roam_record_owner_session (member_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
