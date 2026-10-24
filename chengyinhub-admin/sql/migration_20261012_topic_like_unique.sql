-- This table stores topic likes only; like_type is the state, not another resource namespace.
-- Never discard historical duplicates automatically: export/reconcile their exact rows first.
-- runner: assert=scalar-true
SELECT COUNT(*)=0 FROM (
 SELECT member_id, data_id FROM cms_topic_like
 GROUP BY member_id, data_id HAVING COUNT(*)>1
) duplicated_pairs;
SET @topic_like_unique_exists = (SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='cms_topic_like' AND index_name='uk_topic_like_member_data');
SET @topic_like_unique_ddl = IF(@topic_like_unique_exists=0,
 'ALTER TABLE cms_topic_like ADD UNIQUE KEY uk_topic_like_member_data(member_id,data_id)', 'SELECT 1');
PREPARE topic_like_unique_stmt FROM @topic_like_unique_ddl;
EXECUTE topic_like_unique_stmt;
DEALLOCATE PREPARE topic_like_unique_stmt;
-- runner: assert=scalar-true
SELECT COUNT(*)=2 AND SUM(non_unique=0 AND
 ((seq_in_index=1 AND column_name='member_id') OR (seq_in_index=2 AND column_name='data_id')))=2
FROM information_schema.statistics WHERE table_schema=DATABASE()
 AND table_name='cms_topic_like' AND index_name='uk_topic_like_member_data';
