-- Funds-owned fair queue metadata only. Legacy rows enter at migration time; new rows enter at insert time. Source delivery; no production execution/feature enablement.
SET @w8_column := (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='refund_application' AND column_name='fulfillment_sync_queue_at');
SET @w8_sql := IF(@w8_column=0,
 'ALTER TABLE refund_application ADD COLUMN fulfillment_sync_queue_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT ''F3 recovery queue entry or last attempt time; not proof of successful synchronization''', 'SELECT 1');
PREPARE w8_statement FROM @w8_sql;
EXECUTE w8_statement;
DEALLOCATE PREPARE w8_statement;
SET @w8_index := (SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='refund_application' AND index_name='idx_refund_fulfillment_recovery');
SET @w8_sql := IF(@w8_index=0,
 'CREATE INDEX idx_refund_fulfillment_recovery ON refund_application(source_type,del_flag,fulfillment_sync_queue_at,id)', 'SELECT 1');
PREPARE w8_statement FROM @w8_sql;
EXECUTE w8_statement;
DEALLOCATE PREPARE w8_statement;
-- Fail closed on incompatible existing column/index; never silently change an existing definition.
DROP TEMPORARY TABLE IF EXISTS w8_refund_recovery_guard;
CREATE TEMPORARY TABLE w8_refund_recovery_guard(id INT PRIMARY KEY);
INSERT INTO w8_refund_recovery_guard VALUES(1);
INSERT INTO w8_refund_recovery_guard SELECT 1 WHERE
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='refund_application' AND column_name='fulfillment_sync_queue_at'
  AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO' AND LOWER(column_default)='current_timestamp(6)') <> 1;
INSERT INTO w8_refund_recovery_guard SELECT 1 WHERE
 (SELECT COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index), '') FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='refund_application'
  AND index_name='idx_refund_fulfillment_recovery' AND non_unique=1)
 <> 'source_type,del_flag,fulfillment_sync_queue_at,id';
DROP TEMPORARY TABLE w8_refund_recovery_guard;
