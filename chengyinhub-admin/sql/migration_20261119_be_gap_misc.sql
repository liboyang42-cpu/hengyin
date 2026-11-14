-- be-gap-misc：App 的原生设备核验（App Attest 注册）、私密住所、回家恢复体力、US Apple 登录。
-- 只新增表；不回填、不改既有行；可重复执行。所有功能默认关闭，由配置开关打开（见 docs/delivery/be-gap-misc/RESULT.md）。
-- 私密住所坐标只存于 native_private_home（每个账号一行），核验请求里的设备定位只参与当次判定，不落库。

-- 1) 原生设备核验：questify-be 未合入包（stranded/questify-be-v2-20261020 migration_20261002_native_app_attest）原样搬入。
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

-- 2) 私密住所（GET/PUT/DELETE /api/native/home）：每账号一行 + 每个 requestId 一条回执。
CREATE TABLE IF NOT EXISTS native_private_home (
    member_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DELETED',
    label VARCHAR(40) NULL,
    latitude DECIMAL(9,6) NULL,
    longitude DECIMAL(9,6) NULL,
    datum VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    changed_at BIGINT NULL,
    effective_at BIGINT NULL,
    last_set_at BIGINT NULL COMMENT '最近一次 SAVED；换址冷却只看它，删除不清',
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (member_id),
    CONSTRAINT chk_native_private_home CHECK (member_id > 0 AND version >= 0 AND status IN ('ACTIVE','DELETED')
        AND ((status = 'ACTIVE' AND label IS NOT NULL AND latitude IS NOT NULL AND longitude IS NOT NULL AND datum = 'WGS84')
          OR (status = 'DELETED' AND label IS NULL AND latitude IS NULL AND longitude IS NULL AND datum IS NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原生私密住所（仅本人可读写）';

CREATE TABLE IF NOT EXISTS native_private_home_mutation (
    member_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_version BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (member_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私密住所写入回执（同 requestId 重放）';

-- 3) 回家恢复体力（/api/native/home/return/*）：到家核验（含一次性 proof）与恢复回执。不存设备坐标。
CREATE TABLE IF NOT EXISTS native_home_return_check (
    member_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id BIGINT NOT NULL,
    home_version BIGINT NOT NULL,
    proof_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    challenge_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expires_at BIGINT NULL,
    consumed_at BIGINT NULL,
    response_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (member_id, request_id),
    UNIQUE KEY uk_native_home_return_proof (proof_id),
    KEY idx_native_home_return_challenge (member_id, challenge_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回家核验结果与一次性 proof';

CREATE TABLE IF NOT EXISTS native_home_restore_receipt (
    member_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proof_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id BIGINT NOT NULL,
    decision VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (member_id, request_id),
    KEY idx_native_home_restore_run (member_id, run_id, decision, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回家恢复体力回执（冷却按 RESTORED 计）';

-- 4) US Apple 登录：一次性挑战（只存 raw nonce 的 SHA-256）与 realm 内 sub → 账号绑定。CN 账号从不匹配。
CREATE TABLE IF NOT EXISTS us_apple_challenge (
    challenge_id CHAR(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    realm VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nonce_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state CHAR(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    consumed_at BIGINT NULL,
    PRIMARY KEY (challenge_id),
    KEY idx_us_apple_challenge_open (realm, consumed_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='US Apple 登录一次性挑战';

CREATE TABLE IF NOT EXISTS us_apple_account (
    member_id BIGINT NOT NULL,
    realm VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    apple_sub VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (member_id),
    UNIQUE KEY uk_us_apple_account_sub (realm, apple_sub)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='US Apple 身份与账号绑定（每 realm 一个 sub 一个账号）';
