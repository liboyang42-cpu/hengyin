-- Applicant-only rejection readback; public membership payloads do not serialize these fields.
SET @join_review_reason_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='club_member' AND column_name='review_reason');
SET @join_review_reason_ddl = IF(@join_review_reason_exists=0,
 'ALTER TABLE club_member ADD COLUMN review_reason VARCHAR(500) NOT NULL DEFAULT ''''', 'SELECT 1');
PREPARE join_review_reason_stmt FROM @join_review_reason_ddl;
EXECUTE join_review_reason_stmt;
DEALLOCATE PREPARE join_review_reason_stmt;
SET @join_reviewer_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='club_member' AND column_name='reviewer_member_id');
SET @join_reviewer_ddl = IF(@join_reviewer_exists=0,
 'ALTER TABLE club_member ADD COLUMN reviewer_member_id BIGINT NULL', 'SELECT 1');
PREPARE join_reviewer_stmt FROM @join_reviewer_ddl;
EXECUTE join_reviewer_stmt;
DEALLOCATE PREPARE join_reviewer_stmt;
SET @join_review_at_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='club_member' AND column_name='reviewed_at');
SET @join_review_at_ddl = IF(@join_review_at_exists=0,
 'ALTER TABLE club_member ADD COLUMN reviewed_at DATETIME(6) NULL', 'SELECT 1');
PREPARE join_review_at_stmt FROM @join_review_at_ddl;
EXECUTE join_review_at_stmt;
DEALLOCATE PREPARE join_review_at_stmt;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name='club_member' AND column_name='review_reason' AND data_type='varchar'
 AND character_maximum_length=500 AND is_nullable='NO' AND column_default='';
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name='club_member' AND column_name='reviewer_member_id' AND data_type='bigint' AND is_nullable='YES';
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name='club_member' AND column_name='reviewed_at' AND data_type='datetime'
 AND datetime_precision=6 AND is_nullable='YES';
