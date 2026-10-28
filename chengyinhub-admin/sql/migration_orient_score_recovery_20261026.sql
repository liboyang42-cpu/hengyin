-- W8-06 manual investigation of legacy UNKNOWN without a server request identifier.
-- REVIEWED is an audit conclusion only: never an instruction to replay or unlock the original write.
CREATE TABLE IF NOT EXISTS orient_score_recovery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    reference_id BIGINT DEFAULT NULL,
    resolution_note VARCHAR(500) DEFAULT NULL,
    handled_by VARCHAR(64) DEFAULT NULL,
    handled_at DATETIME(3) DEFAULT NULL,
    create_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orient_recovery_session_kind (session_id,kind),
    KEY idx_orient_recovery_status (status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
