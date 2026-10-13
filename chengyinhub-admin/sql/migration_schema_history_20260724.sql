-- P1-3D migration history metadata contract.
-- This table records identity/checksum/state only. Existing migrations must be reconciled
-- from read-only database observations; repository presence alone never proves APPLIED.
CREATE TABLE cy_migration_history (
    migration_id VARCHAR(191) NOT NULL COMMENT 'migration_ filename stem without prefix',
    migration_path VARCHAR(512) NOT NULL COMMENT 'repository-relative immutable path',
    checksum_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_commit CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    applied_at DATETIME(6) NULL COMMENT 'set only after observed APPLIED or RECONCILED state',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (migration_id),
    UNIQUE KEY uk_cy_migration_history_path (migration_path),
    CONSTRAINT chk_cy_migration_history_status
        CHECK (status IN ('APPLYING', 'APPLIED', 'UNKNOWN', 'RECONCILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Manual migration release ledger; no SQL body or credentials';
