-- Offline source only. Apply after migration_20261027_template_adoption_receipt.sql.
-- No legacy backfill: only a successful locked adoption may establish the inherited configuration.
ALTER TABLE template_adoption_receipt
  ADD COLUMN inherited_advanced_config_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD INDEX idx_adoption_inherited_config (owner_member_id, draft_id, source_id, status);
