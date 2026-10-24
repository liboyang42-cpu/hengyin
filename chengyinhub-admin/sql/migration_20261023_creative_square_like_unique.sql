-- One attitude row per member and square post; like_type is the state (1 like, 2 dislike).
-- Never discard historical duplicates automatically: export/reconcile their exact rows first.
-- runner: assert=scalar-true
SELECT COUNT(*)=0 FROM (
 SELECT member_id, data_id FROM ums_creative_square_like
 GROUP BY member_id, data_id HAVING COUNT(*)>1
) duplicated_pairs;
SET @square_like_unique_exists = (SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='ums_creative_square_like' AND index_name='uk_square_like_member_data');
SET @square_like_unique_ddl = IF(@square_like_unique_exists=0,
 'ALTER TABLE ums_creative_square_like ADD UNIQUE KEY uk_square_like_member_data(member_id,data_id)', 'SELECT 1');
PREPARE square_like_unique_stmt FROM @square_like_unique_ddl;
EXECUTE square_like_unique_stmt;
DEALLOCATE PREPARE square_like_unique_stmt;
-- runner: assert=scalar-true
SELECT COUNT(*)=2 AND SUM(non_unique=0 AND
 ((seq_in_index=1 AND column_name='member_id') OR (seq_in_index=2 AND column_name='data_id')))=2
FROM information_schema.statistics WHERE table_schema=DATABASE()
 AND table_name='ums_creative_square_like' AND index_name='uk_square_like_member_data';
