-- SOURCE ONLY; no production DDL or historical inference authorized.
-- Preflight MySQL8/InnoDB, reserved table/index/check names and existing ums_member row-lock owner.
-- Historical ASR calls have no provable identity: do not backfill an audio hash, owner token or result.
CREATE TABLE npc_asr_intent (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
  node_id BIGINT NOT NULL,
  merchant_member_id BIGINT NOT NULL,
  audio_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  audio_format VARCHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  transcript MEDIUMTEXT NULL,
  body_erased TINYINT NOT NULL DEFAULT 0,
  handoff_started TINYINT NOT NULL DEFAULT 0,
  text_admitted TINYINT NOT NULL DEFAULT 0,
  body_expires_at DATETIME(6) NULL,
  create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  UNIQUE KEY uk_npc_asr_user_request(user_id,request_id),
  KEY idx_npc_asr_body_expiry(body_erased,body_expires_at,id),
  KEY idx_npc_asr_user_body_expiry(user_id,body_erased,body_expires_at,id),
  CONSTRAINT ck_npc_asr_identity CHECK(user_id>0 AND node_id>0 AND merchant_member_id>0),
  CONSTRAINT ck_npc_asr_state CHECK(state IN ('IN_FLIGHT','RECEIVED','NOT_SENT','UNKNOWN')),
  CONSTRAINT ck_npc_asr_flags CHECK(body_erased IN (0,1) AND handoff_started IN (0,1) AND text_admitted IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- Read-only preflight: SHOW CREATE TABLE/SHOW INDEX plus information_schema.columns,table_constraints.
-- Verify unique(user_id,request_id), original UUID ASCII case-insensitive identity, state/flag checks and expiry index.
-- Rollback requires stopping new ASR callers. Keep identities/UNKNOWN/handoff flags; never drop or TTL-delete.
-- Body expiry/deletion redacts text only. Old direct-ASR code must not be re-enabled over retained identities.
