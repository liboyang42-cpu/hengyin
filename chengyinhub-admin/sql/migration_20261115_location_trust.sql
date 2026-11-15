-- ================= migration_20261115_location_trust.sql =================
SELECT 'migration_20261115_location_trust.sql' AS running;
-- P8 location-trust (SEC-04..SEC-10): review trail for every scored location write, and CITY claim photo evidence.
--
-- ROLLBACK (manual, only if the backend build that reads these tables has been rolled back first):
--   DROP TABLE IF EXISTS location_claim_evidence;
--   DROP TABLE IF EXISTS location_trust_fix;
--   DELETE FROM sys_config WHERE config_key LIKE 'location.trust.%';
-- The backend fails open when these tables are missing (writes behave as before, an error is logged),
-- so applying this migration after the deploy is safe; applying it before is also safe (additive only).
--
-- Privacy: location_trust_fix holds raw coordinates. DataRetentionCleanupTask deletes rows older than 30 days
-- (same window as roam tracks) and nulls photo coordinates in location_claim_evidence after 30 days. Photo
-- hashes are kept for duplicate detection; no photo bytes are stored.
-- Idempotent: CREATE TABLE IF NOT EXISTS; config rows inserted only when missing (never overwrite ops values).

CREATE TABLE IF NOT EXISTS location_trust_fix (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  member_id        BIGINT        NOT NULL,
  session_id       BIGINT        DEFAULT NULL       COMMENT 'roam session when the write has one',
  kind             VARCHAR(32)   NOT NULL           COMMENT 'CHECKIN/POI_DISCOVER/SHOP_VISIT/REVEAL/PRESENCE/JOURNEY_START/JOURNEY_NODE/CLAIM_EVIDENCE',
  ref_id           VARCHAR(64)   DEFAULT NULL       COMMENT 'poi/shop/node/evidence id the write was about',
  lat              DECIMAL(10,6) NOT NULL,
  lng              DECIMAL(10,6) NOT NULL,
  accuracy_m       DECIMAL(9,1)  DEFAULT NULL,
  client_ts        DATETIME(3)   DEFAULT NULL       COMMENT 'device fix time',
  received_at      DATETIME(3)   NOT NULL,
  simulated        TINYINT       DEFAULT NULL,
  accessory        TINYINT       DEFAULT NULL,
  speed_mps        DECIMAL(9,2)  DEFAULT NULL       COMMENT 'implied speed from the last clean fix',
  decision         VARCHAR(24)   NOT NULL           COMMENT 'scored: ACCEPT/ACCEPT_NO_REWARD/REJECT',
  applied_decision VARCHAR(24)   NOT NULL           COMMENT 'what the write did (SHADOW mode always ACCEPT)',
  reasons          VARCHAR(255)  DEFAULT NULL,
  detail           VARCHAR(255)  DEFAULT NULL       COMMENT 'e.g. tiles dropped',
  PRIMARY KEY (id),
  KEY idx_member_received (member_id, received_at),
  KEY idx_session_kind (session_id, kind),
  KEY idx_received (received_at),
  KEY idx_decision_received (decision, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P8 location trust: scored location writes (review trail, 30-day retention)';

CREATE TABLE IF NOT EXISTS location_claim_evidence (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  member_id        BIGINT        NOT NULL,
  place_ref        VARCHAR(64)   DEFAULT NULL       COMMENT 'caller-defined place key, e.g. CITY cell/node id',
  place_lat        DECIMAL(10,6) NOT NULL,
  place_lng        DECIMAL(10,6) NOT NULL,
  capture_source   VARCHAR(16)   NOT NULL,
  photo_sha256     CHAR(64)      NOT NULL,
  photo_phash      BIGINT        DEFAULT NULL       COMMENT '64-bit dHash; NULL if the image could not be decoded',
  photo_taken_at   DATETIME(3)   DEFAULT NULL,
  photo_lat        DECIMAL(10,6) DEFAULT NULL       COMMENT 'nulled after 30 days',
  photo_lng        DECIMAL(10,6) DEFAULT NULL       COMMENT 'nulled after 30 days',
  fix_id           BIGINT        DEFAULT NULL       COMMENT 'location_trust_fix row of the fix sent with the evidence',
  verdict          VARCHAR(16)   NOT NULL           COMMENT 'ACCEPT/REVIEW/REJECT',
  reasons          VARCHAR(255)  DEFAULT NULL,
  duplicate_of     BIGINT        DEFAULT NULL,
  consumed_at      DATETIME      DEFAULT NULL       COMMENT 'set once when a claim uses this evidence',
  consumed_ref     VARCHAR(64)   DEFAULT NULL,
  create_time      DATETIME      NOT NULL,
  PRIMARY KEY (id),
  KEY idx_member_time (member_id, create_time),
  KEY idx_sha (photo_sha256),
  KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P8 CITY claim photo evidence (hashes only, no photo bytes)';

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT * FROM (
  SELECT '定位可信-模式' AS n, 'location.trust.mode' AS k, 'ENFORCE' AS v, 'Y' AS t, 'admin' AS b, NOW() AS c,
         'ENFORCE=按判定执行;SHADOW=只记录不拦;OFF=不评分' AS r
  UNION ALL SELECT '定位可信-步行速度上限(m/s)', 'location.trust.footSpeedMps', '15', 'Y', 'admin', NOW(),
         '两次定位间隔 5 分钟内超过即不计奖励(留痕待查)'
  UNION ALL SELECT '定位可信-交通速度上限(m/s)', 'location.trust.vehicleSpeedMps', '35', 'Y', 'admin', NOW(),
         '间隔超过 5 分钟、旅程各站、附近的人心跳用这个上限'
  UNION ALL SELECT '定位可信-打卡精度上限(米)', 'location.trust.pointMaxAccuracyMeters', '200', 'Y', 'admin', NOW(),
         '打卡/探店/发现/占领取证:精度差于此值请玩家到开阔处重试'
  UNION ALL SELECT '定位可信-模拟定位处理', 'location.trust.simulatedAction', 'NO_REWARD', 'Y', 'admin', NOW(),
         'FLAG=只留痕;NO_REWARD=留痕不计奖励;REJECT=拒绝'
  UNION ALL SELECT '定位可信-迷雾格速度(m/s)', 'location.trust.revealSpeedMps', '15', 'Y', 'admin', NOW(),
         '每会话点亮格子预算 = 200 + 时长×速度/100m'
  UNION ALL SELECT '定位可信-取证照片时效(分钟)', 'location.trust.claim.photoMaxAgeMinutes', '15', 'Y', 'admin', NOW(),
         '占领取证照片须在此时间内用相机拍摄'
  UNION ALL SELECT '定位可信-取证半径(米)', 'location.trust.claim.radiusMeters', '300', 'Y', 'admin', NOW(),
         '玩家定位/照片位置须在地点此半径内'
) seed
WHERE NOT EXISTS (SELECT 1 FROM sys_config s WHERE s.config_key = seed.k);

-- runner: assert=scalar-true
SELECT COUNT(*) = 2 FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN ('location_trust_fix', 'location_claim_evidence');
