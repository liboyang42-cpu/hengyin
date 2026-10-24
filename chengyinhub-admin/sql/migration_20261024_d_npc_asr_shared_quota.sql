-- Additive only; DDL NOT EXECUTED. Legacy ASR rows intentionally retain NULL quota identity.
-- Read-only preflight: SHOW CREATE TABLE npc_asr_intent;
-- SELECT COUNT(*) FROM npc_asr_intent;
-- SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
--   AND table_name='npc_asr_intent' AND column_name IN ('quota_reservation','quota_refund_pending');
ALTER TABLE npc_asr_intent
  ADD COLUMN quota_reservation VARCHAR(192) NULL DEFAULT NULL,
  ADD COLUMN quota_refund_pending TINYINT NOT NULL DEFAULT 0,
  ADD INDEX idx_npc_asr_quota_refund (quota_refund_pending,id);
-- Verification: SHOW CREATE TABLE npc_asr_intent;
-- SELECT COUNT(*) FROM npc_asr_intent WHERE quota_reservation IS NULL;
-- Rollback: disable the caller first; retain these columns and all identities/refund intents.
-- Do not reconstruct old reservations, drop tombstones, or roll back to the pre-ASR identity path.
