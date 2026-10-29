-- Canonical candidate only: no production execution, no historical state/voice migration.
-- Exactly one complete CREATE. Precheck all CHECK names across this schema before any DDL.
-- runner: assert=scalar-true
SELECT CAST(IF(
  DATABASE() IS NOT NULL AND CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)=8
  AND (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)>0
    OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=16)
  AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@sql_mode)>0)
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name='npc_voice_enrollment_intent')=0
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND constraint_name IN ('chk_nvei_identity','chk_nvei_state','chk_nvei_voice','chk_nvei_reason'))=0
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='npc_profile'
    AND column_name='voice_version' AND data_type='bigint' AND is_nullable='NO')=1,
  1,JSON_EXTRACT('ASSERT_FAILED_nvei_before_ddl','$')) AS UNSIGNED) AS assertion_ok;

CREATE TABLE npc_voice_enrollment_intent (
  id BIGINT NOT NULL AUTO_INCREMENT,
  npc_id BIGINT NOT NULL,
  scope_type INT NOT NULL,
  scope_id BIGINT NOT NULL,
  actor_member_id BIGINT NOT NULL,
  sample_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sample_reference VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  voice_id VARCHAR(96) NULL,
  reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  profile_version BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_nvei_sample(npc_id,sample_sha256),
  KEY idx_nvei_unresolved(npc_id,state,id),
  CONSTRAINT chk_nvei_identity CHECK (CASE WHEN npc_id>0 AND scope_type IN (2,4) AND scope_id>0
    AND actor_member_id>0 AND profile_version>=0 AND sample_sha256 REGEXP '^[0-9a-f]{64}$'
    AND sample_reference REGEXP '^media-asset:[1-9][0-9]{0,18}$' THEN 1 ELSE 0 END=1),
  CONSTRAINT chk_nvei_state CHECK (state IN ('PREPARED','IN_FLIGHT','UNKNOWN','NOT_SENT','PENDING','READY')),
  CONSTRAINT chk_nvei_voice CHECK (CASE WHEN (state IN ('PENDING','READY') AND voice_id IS NOT NULL
    AND CHAR_LENGTH(TRIM(voice_id))>0) OR (state IN ('PREPARED','IN_FLIGHT','UNKNOWN','NOT_SENT')
    AND voice_id IS NULL) THEN 1 ELSE 0 END=1),
  CONSTRAINT chk_nvei_reason CHECK (CASE WHEN (state='UNKNOWN' AND reason_code='VOICE_PROVIDER_OUTCOME_UNKNOWN')
    OR (state='NOT_SENT' AND reason_code='VOICE_PROVIDER_NOT_SENT')
    OR (state IN ('PREPARED','IN_FLIGHT','PENDING','READY') AND reason_code IS NULL) THEN 1 ELSE 0 END=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- If CREATE succeeded and a later readback fails: preserve the table and all intent rows; inventory first.
-- Never retry by dropping/emptying this table; it is the anti-reenrollment identity, not a response cache.
-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='npc_voice_enrollment_intent')=13
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND table_name='npc_voice_enrollment_intent' AND constraint_type='CHECK' AND enforced='YES')=4
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='npc_voice_enrollment_intent'
    AND index_name='uk_nvei_sample' AND non_unique=0)='npc_id,sample_sha256'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='npc_voice_enrollment_intent'
    AND index_name='idx_nvei_unresolved')='npc_id,state,id',
  1,JSON_EXTRACT('ASSERT_FAILED_nvei_after_ddl','$')) AS UNSIGNED) AS assertion_ok;
