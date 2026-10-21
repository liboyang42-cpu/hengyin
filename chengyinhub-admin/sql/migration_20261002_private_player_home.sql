-- W19 PRIVATE App home. Schema only: no capability, resource adapter or production rules are enabled.
-- Apply only after explicit deployment approval and real MySQL rehearsal. No production connection here.
-- No address, reverse-geocoded label, raw arrival GPS, trail, public-map index or shared actor linkage.
-- owner_id is always derived from the authenticated App session. WGS84 map selection is not residence proof.
-- Historical mutation/restore receipts intentionally contain NO home label/coordinates or their fingerprints.
-- No destructive rollback. Disable capability first; deletion/retention and existing receipts require review.
CREATE TABLE IF NOT EXISTS player_private_home (
    owner_id BIGINT NOT NULL,
    home_version BIGINT NOT NULL,
    label VARCHAR(40) NULL,
    latitude DECIMAL(9,6) NULL,
    longitude DECIMAL(9,6) NULL,
    datum VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    changed_at BIGINT NOT NULL,
    effective_at BIGINT NOT NULL,
    PRIMARY KEY (owner_id),
    CONSTRAINT chk_private_home CHECK (owner_id > 0 AND home_version >= 0 AND changed_at >= 0 AND effective_at >= 0
      AND ((status=_ascii'DELETED' AND label IS NULL AND latitude IS NULL AND longitude IS NULL AND datum IS NULL)
        OR (status=_ascii'ACTIVE' AND label IS NOT NULL AND latitude IS NOT NULL AND longitude IS NOT NULL AND datum IS NOT NULL AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180 AND datum=_ascii'WGS84')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仅本人私人据点，删除清位置并保留版本墓碑';

CREATE TABLE IF NOT EXISTS player_private_home_mutation (
    owner_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_version BIGINT NOT NULL,
    result_version BIGINT NOT NULL,
    decision VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (owner_id,request_id),
    CONSTRAINT chk_private_home_mutation CHECK (owner_id > 0 AND expected_version >= 0 AND result_version >= 0
      AND operation IN (_ascii'SET',_ascii'DELETE') AND created_at > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='据点变更幂等收据，不保存历史坐标或摘要';

CREATE TABLE IF NOT EXISTS player_private_home_proof (
    proof_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT NOT NULL,
    home_version BIGINT NOT NULL,
    auth_epoch BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_revision VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rule_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    grant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    grant_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    consumed_at BIGINT NULL,
    PRIMARY KEY (proof_id),
    UNIQUE KEY uk_private_home_proof_request (owner_id,request_id),
    UNIQUE KEY uk_private_home_proof_grant (owner_id,grant_fingerprint),
    KEY idx_private_home_proof_owner_status (owner_id,status),
    CONSTRAINT chk_private_home_proof CHECK (owner_id > 0 AND home_version > 0 AND auth_epoch >= 0
      AND resource_type IN (_ascii'STAMINA',_ascii'ENERGY') AND action=_ascii'RESTORE_TO_CAP' AND expires_at > issued_at
      AND status IN (_ascii'READY',_ascii'CONSUMED',_ascii'REVOKED',_ascii'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次到家授权，仅存不含位置的权威验证引用';

CREATE TABLE IF NOT EXISTS player_private_home_restore (
    receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT NOT NULL,
    home_version BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proof_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_revision VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rule_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at BIGINT NOT NULL,
    before_value BIGINT NULL,
    after_value BIGINT NULL,
    amount BIGINT NULL,
    PRIMARY KEY (receipt_id),
    UNIQUE KEY uk_private_home_restore_request (owner_id,request_id),
    KEY idx_private_home_restore_cadence (owner_id,resource_scope,decision,created_at),
    CONSTRAINT chk_private_home_restore CHECK (owner_id > 0 AND home_version >= 0 AND created_at > 0
      AND ((decision=_ascii'RESTORED' AND before_value IS NOT NULL AND after_value IS NOT NULL AND amount IS NOT NULL AND before_value >= 0 AND after_value >= before_value AND amount=after_value-before_value)
        OR (decision != _ascii'RESTORED' AND before_value IS NULL AND after_value IS NULL AND amount IS NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源恢复收据与必要账目，不是积分或资金账';

-- 位置证据索引。只有 owner、proof 和到期时刻，没有坐标、标签或原始 evidenceHandle。
CREATE TABLE IF NOT EXISTS player_private_home_location_evidence (
    owner_id BIGINT NOT NULL,
    proof_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at BIGINT NOT NULL,
    PRIMARY KEY (owner_id, proof_id),
    CONSTRAINT chk_private_home_location_evidence CHECK (owner_id > 0 AND expires_at > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='到家证据索引，无坐标';

-- scoped 体力/能量账户。不建资金、赞助或购买权益表，也不自动开户。
CREATE TABLE IF NOT EXISTS player_private_home_resource (
    owner_id BIGINT NOT NULL,
    resource_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_value BIGINT NOT NULL,
    cap_value BIGINT NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (owner_id, resource_scope),
    CONSTRAINT chk_private_home_resource CHECK (owner_id > 0 AND version >= 0
      AND resource_type IN (_ascii'STAMINA',_ascii'ENERGY')
      AND cap_value > 0 AND current_value >= 0 AND current_value <= cap_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='scoped体力或能量账户，不是资金账';

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('player_private_home','player_private_home_mutation','player_private_home_proof','player_private_home_restore','player_private_home_location_evidence','player_private_home_resource') AND engine='InnoDB')=6
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='player_private_home' AND index_name='PRIMARY' AND column_name='owner_id' AND seq_in_index=1 AND non_unique=0)=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='player_private_home_proof' AND index_name='uk_private_home_proof_request' AND non_unique=0)=2
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='player_private_home_restore' AND index_name='uk_private_home_restore_request' AND non_unique=0)=2
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='player_private_home_proof' AND index_name='uk_private_home_proof_grant' AND non_unique=0)=2
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='player_private_home' AND column_name IN ('latitude','longitude') AND data_type='decimal' AND numeric_precision=9 AND numeric_scale=6 AND is_nullable='YES')=2
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name IN ('player_private_home_mutation','player_private_home_proof','player_private_home_restore')
    AND column_name='request_id' AND collation_name='ascii_bin' AND is_nullable='NO')=3
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE()
    AND constraint_name IN ('chk_private_home','chk_private_home_mutation','chk_private_home_proof','chk_private_home_restore') AND constraint_type='CHECK' AND enforced='YES')=4
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='player_private_home' AND index_name='PRIMARY' AND non_unique=0)='owner_id'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='player_private_home_mutation' AND index_name='PRIMARY' AND non_unique=0)='owner_id,request_id'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='player_private_home_proof' AND index_name='uk_private_home_proof_request' AND non_unique=0)='owner_id,request_id'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='player_private_home_restore' AND index_name='uk_private_home_restore_request' AND non_unique=0)='owner_id,request_id'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='player_private_home_proof' AND index_name='uk_private_home_proof_grant' AND non_unique=0)='owner_id,grant_fingerprint'
  AND (SELECT COUNT(*) FROM information_schema.table_constraints tc
    JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
    WHERE tc.constraint_schema=DATABASE() AND tc.constraint_type='CHECK' AND tc.enforced='YES'
      AND ((tc.constraint_name='chk_private_home' AND SHA2(cc.check_clause,256)='699f77253fad4eb1ebae1b3079be03bbba4c51c586bb37d82447d6ee89a564d6')
        OR (tc.constraint_name='chk_private_home_mutation' AND SHA2(cc.check_clause,256)='06a10106ba6fd4ec541583dd8786083d95f2e792e5cbc94ccf0de303da012e6f')
        OR (tc.constraint_name='chk_private_home_proof' AND SHA2(cc.check_clause,256)='c3aaa1b5af48827776a5ee4033f942cf5fd4ef65c92e09605b5e37e783e42bce')
        OR (tc.constraint_name='chk_private_home_restore' AND SHA2(cc.check_clause,256)='da4dd9c403a7589881fd14cfa3ce67ef007abd1455088e634711039fa3d33640')))=4
) AS migration_assertion;
