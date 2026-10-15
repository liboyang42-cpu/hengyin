-- Ended session geometry expires; coordinate-free statistics remain.
SET @roam_cleared_column = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
  AND table_name = 'roam_session' AND column_name = 'location_cleared_at');
SET @roam_cleared_ddl = IF(@roam_cleared_column = 0,
  'ALTER TABLE roam_session ADD COLUMN location_cleared_at DATETIME NULL COMMENT ''30-day geometry cleanup receipt''', 'SELECT 1');
PREPARE roam_cleared_statement FROM @roam_cleared_ddl;
EXECUTE roam_cleared_statement;
DEALLOCATE PREPARE roam_cleared_statement;
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.columns WHERE table_schema = DATABASE()
  AND table_name = 'roam_session' AND column_name = 'location_cleared_at' AND data_type = 'datetime'
  AND is_nullable = 'YES' AND column_default IS NULL;
