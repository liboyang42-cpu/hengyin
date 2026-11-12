-- CT-04: NULL keeps untouched legacy receipts; new APP uploads explicitly record unknown.
-- No backfill, no change to review facts or existing system/template image permissions.
SET @media_source_type_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='source_type');
SET @media_provenance_ddl=IF(@media_source_type_exists=0,'ALTER TABLE app_media_asset ADD COLUMN source_type VARCHAR(24) CHARACTER SET ascii NULL DEFAULT NULL','SELECT 1');
PREPARE media_provenance_stmt FROM @media_provenance_ddl;
EXECUTE media_provenance_stmt;
DEALLOCATE PREPARE media_provenance_stmt;
SET @media_license_note_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='license_note');
SET @media_provenance_ddl=IF(@media_license_note_exists=0,'ALTER TABLE app_media_asset ADD COLUMN license_note VARCHAR(2000) NULL','SELECT 1');
PREPARE media_provenance_stmt FROM @media_provenance_ddl;
EXECUTE media_provenance_stmt;
DEALLOCATE PREPARE media_provenance_stmt;
SET @media_attribution_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='attribution');
SET @media_provenance_ddl=IF(@media_attribution_exists=0,'ALTER TABLE app_media_asset ADD COLUMN attribution VARCHAR(255) NULL','SELECT 1');
PREPARE media_provenance_stmt FROM @media_provenance_ddl;
EXECUTE media_provenance_stmt;
DEALLOCATE PREPARE media_provenance_stmt;
SET @media_ai_model_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='ai_model');
SET @media_provenance_ddl=IF(@media_ai_model_exists=0,'ALTER TABLE app_media_asset ADD COLUMN ai_model VARCHAR(100) NULL','SELECT 1');
PREPARE media_provenance_stmt FROM @media_provenance_ddl;
EXECUTE media_provenance_stmt;
DEALLOCATE PREPARE media_provenance_stmt;
SET @media_source_created_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='source_created_at');
SET @media_provenance_ddl=IF(@media_source_created_exists=0,'ALTER TABLE app_media_asset ADD COLUMN source_created_at DATETIME NULL','SELECT 1');
PREPARE media_provenance_stmt FROM @media_provenance_ddl;
EXECUTE media_provenance_stmt;
DEALLOCATE PREPARE media_provenance_stmt;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='source_type' AND data_type='varchar' AND character_maximum_length=24 AND is_nullable='YES' AND column_default IS NULL;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='license_note' AND data_type='varchar' AND character_maximum_length=2000 AND is_nullable='YES';
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='attribution' AND data_type='varchar' AND character_maximum_length=255 AND is_nullable='YES';
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='ai_model' AND data_type='varchar' AND character_maximum_length=100 AND is_nullable='YES';
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='source_created_at' AND data_type='datetime' AND is_nullable='YES';
