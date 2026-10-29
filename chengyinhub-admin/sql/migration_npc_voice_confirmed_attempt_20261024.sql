-- Candidate only; never execute automatically against production.
-- Historical sample identity is deterministically named, never deleted or guessed from provider outcome.
-- runner: assert=scalar-true
SELECT CAST(IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='npc_voice_enrollment_intent')=13
 AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='npc_voice_enrollment_intent' AND index_name='uk_nvei_sample'
  AND non_unique=0)='npc_id,sample_sha256'
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='npc_voice_enrollment_intent' AND column_name IN ('request_id','previous_intent_id'))=0,
 1,JSON_EXTRACT('ASSERT_FAILED_voice_request_preflight','$')) AS UNSIGNED) AS assertion_ok;
ALTER TABLE npc_voice_enrollment_intent
 ADD COLUMN request_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN previous_intent_id BIGINT NULL;
UPDATE npc_voice_enrollment_intent SET request_id=CONCAT('sample:',sample_sha256) WHERE request_id IS NULL;
-- The old sample fingerprint remains unchanged; only the request becomes the unique business identity.
ALTER TABLE npc_voice_enrollment_intent
 MODIFY COLUMN request_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ADD UNIQUE KEY uk_nvei_request(npc_id,request_id),
 ADD KEY idx_nvei_sample(npc_id,sample_sha256),
 ADD CONSTRAINT chk_nvei_request CHECK (
  (request_id=CONCAT('sample:',sample_sha256) AND previous_intent_id IS NULL)
  OR (request_id REGEXP '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
      AND (previous_intent_id IS NULL OR previous_intent_id>0))),
 DROP INDEX uk_nvei_sample;
-- runner: assert=scalar-true
SELECT CAST(IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='npc_voice_enrollment_intent')=15
 AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='npc_voice_enrollment_intent' AND index_name='uk_nvei_request'
  AND non_unique=0)='npc_id,request_id'
 AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
  AND table_name='npc_voice_enrollment_intent' AND constraint_type='CHECK' AND enforced='YES')=5
 AND NOT EXISTS(SELECT 1 FROM npc_voice_enrollment_intent WHERE request_id IS NULL),
 1,JSON_EXTRACT('ASSERT_FAILED_voice_request_readback','$')) AS UNSIGNED) AS assertion_ok;
-- Rollback: roll application forward or stop new enrollment. Do not restore sample uniqueness after independent attempts
-- exist, drop intents, reset UNKNOWN, rewrite fingerprints/provider ids, or refund/release original occupancy.
