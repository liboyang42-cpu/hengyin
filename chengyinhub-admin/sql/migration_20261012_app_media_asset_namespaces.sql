-- Backend staff and App members are different principals even when numeric IDs match.
SET @media_owner_type_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='owner_type');
SET @media_owner_type_ddl=IF(@media_owner_type_exists=0,'ALTER TABLE app_media_asset ADD COLUMN owner_type VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT ''APP'', ADD COLUMN storage_type VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT ''OSS'', ADD COLUMN public_url VARCHAR(1024) NULL','SELECT 1');
PREPARE media_namespace_statement FROM @media_owner_type_ddl;
EXECUTE media_namespace_statement;
DEALLOCATE PREPARE media_namespace_statement;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='owner_type' AND column_default='APP' AND is_nullable='NO';
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='storage_type' AND column_default='OSS' AND is_nullable='NO';
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset' AND column_name='public_url' AND character_maximum_length=1024 AND is_nullable='YES';
