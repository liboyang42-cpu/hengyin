-- Old shares have no provable session association; never infer one from client_ts.
SET @roam_share_session_column = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND column_name = 'session_id');
SET @roam_share_session_ddl = IF(@roam_share_session_column = 0,
  'ALTER TABLE roam_share_snapshot ADD COLUMN session_id BIGINT NULL COMMENT ''Owned finished roam session for location retention''', 'SELECT 1');
PREPARE roam_share_session_statement FROM @roam_share_session_ddl;
EXECUTE roam_share_session_statement;
DEALLOCATE PREPARE roam_share_session_statement;
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.columns WHERE table_schema = DATABASE()
  AND table_name = 'roam_share_snapshot' AND column_name = 'session_id' AND data_type = 'bigint'
  AND is_nullable = 'YES' AND column_default IS NULL;
SET @roam_share_session_index = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND index_name = 'idx_roam_share_session');
SET @roam_share_index_ddl = IF(@roam_share_session_index = 0,
  'ALTER TABLE roam_share_snapshot ADD INDEX idx_roam_share_session (session_id, member_id)', 'SELECT 1');
PREPARE roam_share_index_statement FROM @roam_share_index_ddl;
EXECUTE roam_share_index_statement;
DEALLOCATE PREPARE roam_share_index_statement;
-- runner: assert=scalar-true
SELECT COUNT(*) = 2 AND SUM(non_unique = 1 AND ((seq_in_index = 1 AND column_name = 'session_id')
  OR (seq_in_index = 2 AND column_name = 'member_id'))) = 2 FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND index_name = 'idx_roam_share_session';
