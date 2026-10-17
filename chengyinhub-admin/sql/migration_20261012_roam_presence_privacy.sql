-- Existing and new sessions stay private until the member separately agrees.
SET @presence_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'roam_session' AND column_name = 'presence_visible'
);
SET @presence_ddl = IF(@presence_column_exists = 0,
    'ALTER TABLE roam_session ADD COLUMN presence_visible TINYINT NOT NULL DEFAULT 0 COMMENT ''Nearby people sharing: explicit consent only''',
    'SELECT 1');
PREPARE presence_statement FROM @presence_ddl;
EXECUTE presence_statement;
DEALLOCATE PREPARE presence_statement;
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'roam_session' AND column_name = 'presence_visible'
  AND data_type = 'tinyint' AND is_nullable = 'NO' AND column_default = '0';
