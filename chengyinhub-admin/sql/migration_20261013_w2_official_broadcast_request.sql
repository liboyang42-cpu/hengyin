-- W2 original F0513: nullable recovery keys preserve legacy broadcasts.
-- Apply only to an authorized development/test database; production requires separate approval.

SET @w2_bc_column_exists := (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='official_broadcast' AND column_name='request_scope');
SET @w2_bc_sql := IF(@w2_bc_column_exists=0,
 'ALTER TABLE official_broadcast ADD COLUMN request_scope VARCHAR(40) COLLATE utf8mb4_bin NULL', 'SELECT 1');
PREPARE w2_bc_stmt FROM @w2_bc_sql;
EXECUTE w2_bc_stmt;
DEALLOCATE PREPARE w2_bc_stmt;

SET @w2_bc_column_exists := (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='official_broadcast' AND column_name='request_id');
SET @w2_bc_sql := IF(@w2_bc_column_exists=0,
 'ALTER TABLE official_broadcast ADD COLUMN request_id VARCHAR(64) COLLATE utf8mb4_bin NULL', 'SELECT 1');
PREPARE w2_bc_stmt FROM @w2_bc_sql;
EXECUTE w2_bc_stmt;
DEALLOCATE PREPARE w2_bc_stmt;

SET @w2_bc_index_exists := (SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='official_broadcast' AND index_name='uk_broadcast_request');
SET @w2_bc_sql := IF(@w2_bc_index_exists=0,
 'ALTER TABLE official_broadcast ADD UNIQUE KEY uk_broadcast_request (request_scope,request_id)', 'SELECT 1');
PREPARE w2_bc_stmt FROM @w2_bc_sql;
EXECUTE w2_bc_stmt;
DEALLOCATE PREPARE w2_bc_stmt;
