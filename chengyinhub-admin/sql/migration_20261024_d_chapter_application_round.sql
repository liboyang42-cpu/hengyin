-- Add one nullable attempt identity. Existing rows remain NULL: no historical invitation is inferred or resent.
-- Prerequisites: controlled MySQL 8.0.16+, original application table/InnoDB/unique chapter+merchant key verified.
-- Source candidate only; coordinator reviews/executes DDL separately. No business/history/receipt UPDATE or DELETE.
ALTER TABLE merchant_chapter_application
    ADD COLUMN application_round BIGINT NULL DEFAULT NULL
        COMMENT 'Observed application attempt; legacy NULL is unverified, not implicit round 1',
    ADD CONSTRAINT ck_merchant_application_round
        CHECK (application_round IS NULL OR application_round >= 1);

-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='merchant_chapter_application'
  AND column_name='application_round' AND data_type='bigint' AND is_nullable='YES'
  AND column_default IS NULL;

-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.table_constraints
WHERE constraint_schema=DATABASE() AND table_name='merchant_chapter_application'
  AND constraint_name='ck_merchant_application_round' AND constraint_type='CHECK' AND enforced='YES';

-- Keep this column and every original intent on rollback; do not re-enable writers which reuse a known round.
