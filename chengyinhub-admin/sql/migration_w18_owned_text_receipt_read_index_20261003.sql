-- Inactive installed-receipt reader only. Requires w18_owned_text_draft_install_20261002.
-- Adds one owner/draft/history-order index; no data rewrite, content read or reader activation.
-- A same-named incompatible index is rejected by the scalar assertion, never silently repaired.
-- Reject missing/nontransactional target tables before any index DDL.
-- runner: assert=scalar-true
SELECT ((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
  AND table_name='w18_module_owned_draft' AND table_type='BASE TABLE' AND engine='InnoDB')=1) AS migration_assertion;
SET @w18_receipt_read_index_exists := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='w18_module_owned_draft'
    AND index_name='idx_w18_owned_receipt_read');
SET @w18_receipt_read_index_sql := IF(@w18_receipt_read_index_exists=0,
  'ALTER TABLE w18_module_owned_draft ADD INDEX idx_w18_owned_receipt_read (buyer_kind,buyer_id,target_draft_id,installed_at,id)',
  'SELECT 1');
PREPARE w18_receipt_read_index_stmt FROM @w18_receipt_read_index_sql;
EXECUTE w18_receipt_read_index_stmt;
DEALLOCATE PREPARE w18_receipt_read_index_stmt;
-- runner: assert=scalar-true
SELECT ((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
  AND table_name='w18_module_owned_draft' AND table_type='BASE TABLE' AND engine='InnoDB')=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='w18_module_owned_draft' AND index_name='idx_w18_owned_receipt_read')=5
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='w18_module_owned_draft' AND index_name='idx_w18_owned_receipt_read'
    AND non_unique=1 AND sub_part IS NULL AND expression IS NULL AND is_visible='YES'
    AND index_type='BTREE' AND collation='A'
    AND ((column_name='buyer_kind' AND seq_in_index=1)
      OR (column_name='buyer_id' AND seq_in_index=2)
      OR (column_name='target_draft_id' AND seq_in_index=3)
      OR (column_name='installed_at' AND seq_in_index=4)
      OR (column_name='id' AND seq_in_index=5)))=5) AS migration_assertion;
