-- iPhone native audit provider: schema only, no feature activation, keys, certificates or production data.
-- Apply only after authorized deployment preflight. No foreign-key or authentication policy changes.
-- Key IDs are global/case-sensitive, never reassigned; all runtime access also requires member_id.
-- Rollback requires explicit review of enrolled data; no destructive rollback is executed by this file.
CREATE TABLE IF NOT EXISTS native_app_attest_guard (
    member_id BIGINT NOT NULL,
    day_key CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_count INT NOT NULL DEFAULT 0,
    last_issued_at BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (member_id),
    CONSTRAINT chk_native_attest_guard CHECK (member_id > 0 AND issued_count >= 0 AND last_issued_at >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原生设备注册账号级串行限流';

CREATE TABLE IF NOT EXISTS native_app_attest_key (
    key_id CHAR(44) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_id BIGINT NOT NULL,
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    app_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    public_key_spki VARBINARY(128) NOT NULL,
    receipt BLOB NOT NULL,
    assertion_counter BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bundle_version VARCHAR(64) NOT NULL,
    distribution_category INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (key_id),
    KEY idx_native_attest_owner_status (member_id,status),
    CONSTRAINT chk_native_attest_key CHECK (member_id > 0 AND assertion_counter BETWEEN 0 AND 4294967295
        AND status IN ('ACTIVE','REVOKED') AND environment='production'
        AND provider='IOS_CMPEDOMETER_V1' AND created_at > 0 AND updated_at > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原生设备已验证公钥与全局断言计数器（不含私钥）';

CREATE TABLE IF NOT EXISTS native_app_attest_challenge (
    challenge_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_id BIGINT NOT NULL,
    key_id CHAR(44) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    app_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nonce VARBINARY(32) NOT NULL,
    issued_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    consumed_at BIGINT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (challenge_id),
    KEY idx_native_attest_challenge_owner_expiry (member_id,expires_at),
    CONSTRAINT chk_native_attest_challenge CHECK (member_id > 0 AND expires_at > issued_at
        AND environment='production' AND provider='IOS_CMPEDOMETER_V1'
        AND ((consumed_at IS NULL AND request_digest IS NULL) OR (consumed_at IS NOT NULL AND request_digest IS NOT NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原生设备注册单次挑战与有限期幂等回执';

-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
        AND table_name IN ('native_app_attest_guard','native_app_attest_key','native_app_attest_challenge') AND engine='InnoDB')=3
    AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='native_app_attest_key' AND index_name='PRIMARY' AND column_name='key_id' AND seq_in_index=1 AND non_unique=0)=1
    AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='native_app_attest_key' AND index_name='PRIMARY')=1
    AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='native_app_attest_guard' AND index_name='PRIMARY')=1
    AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='native_app_attest_guard' AND index_name='PRIMARY' AND column_name='member_id' AND seq_in_index=1)=1
    AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='native_app_attest_challenge' AND index_name='PRIMARY')=1
    AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='native_app_attest_challenge' AND index_name='PRIMARY' AND column_name='challenge_id' AND seq_in_index=1)=1
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='native_app_attest_key' AND column_name='key_id' AND collation_name='ascii_bin' AND is_nullable='NO')=1
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='native_app_attest_key' AND column_name='assertion_counter' AND data_type='bigint' AND is_nullable='NO')=1
    AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE()
        AND constraint_name IN ('chk_native_attest_guard','chk_native_attest_key','chk_native_attest_challenge') AND constraint_type='CHECK')=3
) AS migration_assertion;
