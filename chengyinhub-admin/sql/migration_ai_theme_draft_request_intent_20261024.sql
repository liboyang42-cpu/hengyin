-- Theme Draft v2 candidate only. No production execution, data rewrite or job activation.
-- Fail closed BEFORE the only DDL: CHECK names are schema-global, indexes are table-scoped.
-- runner: assert=scalar-true
SELECT CAST(IF(
  DATABASE() IS NOT NULL
  AND CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)=8
  AND (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)>0
       OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=16)
  AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@sql_mode)>0)
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
       AND table_name='ai_theme_draft_request_intent')=0
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
       AND constraint_name IN ('chk_atdi_v2_identity','chk_atdi_v2_plan','chk_atdi_v2_state',
         'chk_atdi_v2_claim','chk_atdi_v2_result','chk_atdi_v2_facts','chk_atdi_v2_ttl'))=0,
  1, JSON_EXTRACT('ASSERT_FAILED__atdi_v2_before_ddl','$')
) AS UNSIGNED) AS assertion_ok;

CREATE TABLE ai_theme_draft_request_intent (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  prepare_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  product_type INT NULL,
  plan_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  pool VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  original_day CHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  counter_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  nonce CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  receipt_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reservation VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  creator_config_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  gateway_config_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  claim_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  outcome_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  claimed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3) NULL,
  execute_before DATETIME(3) NOT NULL,
  context_expires_at DATETIME(3) NOT NULL,
  result_expires_at DATETIME(3) NULL,
  context_purged_at DATETIME(3) NULL,
  result_purged_at DATETIME(3) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_atdi_v2_prepare(member_id,prepare_key),
  UNIQUE KEY uk_atdi_v2_request(request_id),
  UNIQUE KEY uk_atdi_v2_nonce(nonce),
  UNIQUE KEY uk_atdi_v2_receipt(receipt_key),
  KEY idx_atdi_v2_context(context_purged_at,context_expires_at,id),
  KEY idx_atdi_v2_result(result_purged_at,result_expires_at,id),
  KEY idx_atdi_v2_recovery(member_id,id),
  CONSTRAINT chk_atdi_v2_identity CHECK (CASE WHEN member_id>0
    AND prepare_key REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    AND request_id REGEXP '^td2_[0-9a-f]{32}$' AND request_hash REGEXP '^[0-9a-f]{64}$'
    AND plan_hash REGEXP '^[0-9a-f]{64}$' AND nonce REGEXP '^[0-9a-f]{32}$'
    AND creator_config_hash REGEXP '^[0-9a-f]{64}$' AND gateway_config_hash REGEXP '^[0-9a-f]{64}$' THEN 1 ELSE 0 END=1),
  CONSTRAINT chk_atdi_v2_plan CHECK (CASE WHEN CHAR_LENGTH(role)>0 AND original_day REGEXP '^[0-9]{8}$'
    AND (product_type IS NULL OR product_type IN (1,2))
    AND ((role='player' AND pool='player-draft'
      AND counter_key=CONCAT('ai:theme:draft:player:',member_id,':',original_day)
      AND receipt_key=CONCAT('ai:player:theme:draft:reservation:',counter_key,':',nonce))
     OR (role<>'player' AND pool='gen'
      AND counter_key=CONCAT('ai:gen:limit:gen:',member_id,':',original_day)
      AND receipt_key=CONCAT('ai:gen:reservation:',counter_key,':',nonce)))
    AND reservation=CONCAT(counter_key,'|',nonce) THEN 1 ELSE 0 END=1),
  CONSTRAINT chk_atdi_v2_state CHECK (CASE WHEN state IN ('PREPARED','PROCESSING','SUCCEEDED','FAILED','UNKNOWN')
    AND (provider_state IS NULL OR provider_state IN ('NOT_SENT','UNKNOWN','RECEIVED')) THEN 1 ELSE 0 END=1),
  CONSTRAINT chk_atdi_v2_claim CHECK (CASE WHEN
    ((claim_token IS NULL AND claimed_at IS NULL) OR (claim_token REGEXP '^[0-9a-f]{32}$' AND claimed_at IS NOT NULL))
    AND (state<>'PREPARED' OR claim_token IS NULL)
    AND (state NOT IN ('PROCESSING','SUCCEEDED','UNKNOWN') OR claim_token IS NOT NULL) THEN 1 ELSE 0 END=1),
  CONSTRAINT chk_atdi_v2_result CHECK (CASE WHEN
    (state='SUCCEEDED' AND provider_state='RECEIVED' AND result_hash IS NOT NULL
      AND result_hash REGEXP '^[0-9a-f]{64}$' AND result_expires_at IS NOT NULL)
    OR (state<>'SUCCEEDED' AND result_hash IS NULL AND result_expires_at IS NULL) THEN 1 ELSE 0 END=1),
  CONSTRAINT chk_atdi_v2_facts CHECK (CASE WHEN
    ((state IN ('PREPARED','PROCESSING') AND completed_at IS NULL AND outcome_hash IS NULL)
     OR (state IN ('SUCCEEDED','FAILED','UNKNOWN') AND completed_at IS NOT NULL
       AND outcome_hash IS NOT NULL AND outcome_hash REGEXP '^[0-9a-f]{64}$'))
    AND (state<>'UNKNOWN' OR provider_state='UNKNOWN') THEN 1 ELSE 0 END=1),
  CONSTRAINT chk_atdi_v2_ttl CHECK (CASE WHEN context_expires_at=DATE_ADD(created_at,INTERVAL 24 HOUR)
    AND execute_before>created_at AND execute_before<=context_expires_at THEN 1 ELSE 0 END=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- No JSON/TEXT/body columns. Any failed postcheck leaves the feature OFF; inventory before recovery.
-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='ai_theme_draft_request_intent')=31
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='ai_theme_draft_request_intent' AND column_name='product_type' AND data_type='int' AND is_nullable='YES')=1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='ai_theme_draft_request_intent' AND data_type IN ('json','text','mediumtext','longtext'))=0
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND table_name='ai_theme_draft_request_intent' AND constraint_type='CHECK' AND enforced='YES')=7
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='ai_theme_draft_request_intent' AND index_name='uk_atdi_v2_prepare' AND non_unique=0)='member_id,prepare_key'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='ai_theme_draft_request_intent' AND index_name='uk_atdi_v2_request' AND non_unique=0)='request_id'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='ai_theme_draft_request_intent' AND index_name='uk_atdi_v2_nonce' AND non_unique=0)='nonce'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='ai_theme_draft_request_intent' AND index_name='uk_atdi_v2_receipt' AND non_unique=0)='receipt_key'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='ai_theme_draft_request_intent' AND index_name='idx_atdi_v2_context')='context_purged_at,context_expires_at,id'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='ai_theme_draft_request_intent' AND index_name='idx_atdi_v2_result')='result_purged_at,result_expires_at,id'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='ai_theme_draft_request_intent' AND index_name='idx_atdi_v2_recovery')='member_id,id',
  1, JSON_EXTRACT('ASSERT_FAILED__atdi_v2_after_ddl','$')
) AS UNSIGNED) AS assertion_ok;
-- Partial-DDL recovery is inspect-only: never DROP/ALTER someone else's or a nonempty table.
-- Empty rollback requires closed admission and proven exclusive empty ownership; leave metadata otherwise.
