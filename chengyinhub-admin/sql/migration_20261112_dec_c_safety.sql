-- Callback risk is sticky for the immutable uploaded bytes; re-upload replacement bytes instead of retrying a known violation.
SET @dec_c_safety_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='app_media_asset' AND COLUMN_NAME='safety_rejected')=0,
  'ALTER TABLE app_media_asset ADD COLUMN safety_rejected TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''Sticky machine safety rejection''',
  'SELECT 1');
PREPARE dec_c_safety_stmt FROM @dec_c_safety_ddl;
EXECUTE dec_c_safety_stmt;
DEALLOCATE PREPARE dec_c_safety_stmt;
