-- Additive extension of D's existing ledger only. DO NOT execute without coordinator DDL approval.
-- Preflight: D migration installed; old rows all have im_intent_id>0, valid request_hash/state/owner;
-- no event_key with reserved prefix 'before-start:'; preserve every old row/hash/unique key.
-- Operational cutover: pause job 102; retain legacy success keys; unresolved pre-ledger attempts need
-- provider/manual verification. Absence of the old two-day Redis key is NOT proof of no historical send.
ALTER TABLE committed_official_notice_wx
    ADD COLUMN notice_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OFFICIAL_BROADCAST',
    ADD COLUMN wx_payload_json MEDIUMTEXT NULL,
    MODIFY COLUMN im_intent_id BIGINT NULL,
    DROP CHECK ck_committed_official_notice_recipient,
    ADD CONSTRAINT ck_committed_official_notice_typed CHECK (
        recipient > 0 AND (
            (notice_type='OFFICIAL_BROADCAST' AND im_intent_id IS NOT NULL AND im_intent_id > 0 AND wx_payload_json IS NULL)
            OR (notice_type='BEFORE_START' AND im_intent_id IS NULL AND wx_payload_json IS NOT NULL AND JSON_VALID(wx_payload_json))
        )
    ),
    ADD KEY idx_committed_official_notice_typed_pending (notice_type,wx_state,id);
-- Original PENDING/UNKNOWN/ACCEPTED checks, event identity and owner-token CAS are unchanged.
-- No backfill, deletion, job enabling, UNKNOWN reset or external action.
-- Rollback: stop producers/dispatch first. Once BEFORE_START rows exist, retain this schema and typed owner;
-- do not drop payload/identity, manufacture IM ids, or run an old dispatcher over these rows.
