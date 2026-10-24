-- Extends the preceding new seat table to standalone activity seats. No backfill or money update.
-- Existing topic/group seats keep their original positive topic_id. Standalone activities have NULL
-- topic_id; an activity id must never be substituted for it. G's topic-scope reader excludes NULL.
-- Execute only in a separately authorized migration after 20261024_registration_verified_seats.
ALTER TABLE cms_registration_seat
    DROP CHECK chk_registration_seat_fact,
    ADD CONSTRAINT chk_registration_seat_fact CHECK (
    (status = 0 AND topic_id IS NULL AND activity_id IS NULL AND verifier_member_id IS NULL AND verified_at IS NULL)
    OR (status = 1 AND (topic_id IS NULL OR topic_id > 0)
        AND activity_id IS NOT NULL AND activity_id > 0
        AND verifier_member_id IS NOT NULL AND verifier_member_id > 0 AND verified_at IS NOT NULL)
);
-- Rollback requires disabling standalone activity issuance first; keep any actual seat evidence.
-- Do not restore the prior constraint by deleting/rewriting standalone facts.
