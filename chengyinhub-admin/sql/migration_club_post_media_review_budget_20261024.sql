-- Follow-up to migration_club_role_media_review_outbox_20260824.sql.
-- Source only: run the read-only preflight separately before approving any production DDL.
-- No state renumbering, no task deletion/reset, no content mutation and no dispatch in this migration.
-- State 5 remains stopped/manual. Keep the original claim owner/fence and known trace; never requeue UNKNOWN.
-- MySQL 8 atomic ALTER supports repeating the same complete statement after uncertain DDL acknowledgement.

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AND SUM(t.engine = 'InnoDB') = 1 AS assertion_ok
FROM information_schema.tables t
WHERE t.table_schema = DATABASE() AND t.table_name = 'club_post_media_review_outbox';

-- Accept exactly the original check or this follow-up check, never an arbitrary same-name constraint.
-- runner: assert=scalar-true
SELECT COUNT(*) = 2 AND SUM(CASE
  WHEN tc.constraint_name = 'chk_club_post_media_review_trace' AND tc.enforced = 'YES'
    AND REGEXP_REPLACE(LOWER(cc.check_clause), '[[:space:]`\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '') IN
    ('ifstate=2,trace_hashisnotnull,trace_hashisnull=1',
     'state=2andtrace_hashisnotnullorstate=5orstatenotin2,5andtrace_hashisnull') THEN 1
  WHEN tc.constraint_name = 'chk_club_post_media_review_owner' AND tc.enforced = 'YES'
    AND REGEXP_REPLACE(LOWER(cc.check_clause), '[[:space:]`\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '') IN
    ('ifstate=1,owner_tokenisnotnull,owner_tokenisnull=1',
     'state=1andowner_tokenisnotnullorstate=5orstatenotin1,5andowner_tokenisnull') THEN 1
  ELSE 0 END) = 2 AS assertion_ok
FROM information_schema.check_constraints cc
JOIN information_schema.table_constraints tc
  ON tc.constraint_schema = cc.constraint_schema AND tc.constraint_name = cc.constraint_name
WHERE cc.constraint_schema = DATABASE() AND tc.table_name = 'club_post_media_review_outbox'
  AND tc.constraint_name IN ('chk_club_post_media_review_trace','chk_club_post_media_review_owner')
  AND tc.constraint_type = 'CHECK';

ALTER TABLE club_post_media_review_outbox
  DROP CHECK chk_club_post_media_review_trace,
  DROP CHECK chk_club_post_media_review_owner,
  ADD CONSTRAINT chk_club_post_media_review_owner CHECK
    ((state = 1 AND owner_token IS NOT NULL) OR state = 5
      OR (state NOT IN (1,5) AND owner_token IS NULL)),
  ADD CONSTRAINT chk_club_post_media_review_trace CHECK
    ((state = 2 AND trace_hash IS NOT NULL) OR state = 5
      OR (state NOT IN (2,5) AND trace_hash IS NULL));

-- runner: assert=scalar-true
SELECT COUNT(*) = 2 AND SUM(CASE
  WHEN tc.constraint_name = 'chk_club_post_media_review_trace' AND tc.enforced = 'YES'
    AND REGEXP_REPLACE(LOWER(cc.check_clause), '[[:space:]`\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '') =
    'state=2andtrace_hashisnotnullorstate=5orstatenotin2,5andtrace_hashisnull' THEN 1
  WHEN tc.constraint_name = 'chk_club_post_media_review_owner' AND tc.enforced = 'YES'
    AND REGEXP_REPLACE(LOWER(cc.check_clause), '[[:space:]`\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '') =
    'state=1andowner_tokenisnotnullorstate=5orstatenotin1,5andowner_tokenisnull' THEN 1
  ELSE 0 END) = 2 AS assertion_ok
FROM information_schema.check_constraints cc
JOIN information_schema.table_constraints tc
  ON tc.constraint_schema = cc.constraint_schema AND tc.constraint_name = cc.constraint_name
WHERE cc.constraint_schema = DATABASE() AND tc.table_name = 'club_post_media_review_outbox'
  AND tc.constraint_name IN ('chk_club_post_media_review_trace','chk_club_post_media_review_owner')
  AND tc.constraint_type = 'CHECK';

-- Read-only operational inventory. Expired PROCESSING is uncertain even below the attempt budget.
SELECT id,post_id,post_version,submitter_member_id,state,attempt_count,max_attempts,
       HEX(media_hash) media_hash,HEX(trace_hash) trace_hash,last_error_code
FROM club_post_media_review_outbox
WHERE state IN (1,3,5) OR attempt_count >= LEAST(max_attempts,5)
ORDER BY id;
-- Rollback is not DELETE or resetting attempt_count. Stop dispatch and retain this expanded CHECK.
-- Do not restore the old CHECKs while stopped rows have an original owner/trace; that would reject preserved facts.
-- Do not roll back to the old dispatcher that blindly reclaims expired PROCESSING/ambiguous RETRYABLE.
