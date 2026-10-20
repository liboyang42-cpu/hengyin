-- ISOLATED EVALUATION ONLY. Forward source proposal, not executed on production.
-- No reward authority, permissions, production flag, grant, publisher or source adapter is created.
CREATE TABLE w14_egg_evaluation_scope (
  evaluation_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  member_id BIGINT NOT NULL,
  scope_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  release_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  occurrence_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  definition_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  definition_version BIGINT NOT NULL,
  definition_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evidence_level VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  PRIMARY KEY (evaluation_key),
  UNIQUE KEY uk_w14_egg_scope_owner (evaluation_key,member_id),
  CONSTRAINT ck_w14_egg_scope CHECK (scope_kind IN ('THEME','MAP') AND member_id>0 AND definition_version>0
    AND revision>0 AND status IN ('ACTIVE','REVOKED') AND evidence_level='ISOLATED_EVALUATION')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE w14_egg_evaluation_receipt (
  evaluation_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  member_id BIGINT NOT NULL,
  receipt_id VARCHAR(68) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  definition_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  discovered_at VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  history_hashes MEDIUMTEXT NOT NULL,
  evidence_level VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (evaluation_key),
  UNIQUE KEY uk_w14_egg_receipt (receipt_id),
  CONSTRAINT fk_w14_egg_receipt_scope FOREIGN KEY (evaluation_key,member_id)
    REFERENCES w14_egg_evaluation_scope(evaluation_key,member_id),
  CONSTRAINT ck_w14_egg_receipt CHECK (evidence_level='ISOLATED_EVALUATION')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE w14_egg_evaluation_outbox (
  event_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evaluation_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  event_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evidence_level VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  recorded_at VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (event_id),
  UNIQUE KEY uk_w14_egg_outbox_effect (evaluation_key,event_type),
  CONSTRAINT fk_w14_egg_outbox_scope FOREIGN KEY (evaluation_key) REFERENCES w14_egg_evaluation_scope(evaluation_key),
  CONSTRAINT ck_w14_egg_outbox CHECK (evidence_level='ISOLATED_EVALUATION'
    AND event_type IN ('EVALUATED_STORY_DISCOVERY','EVALUATION_REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SELECT (COUNT(*)=3) FROM information_schema.tables WHERE table_schema=DATABASE()
  AND table_name IN ('w14_egg_evaluation_scope','w14_egg_evaluation_receipt','w14_egg_evaluation_outbox') AND engine='InnoDB';
