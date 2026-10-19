-- Inactive W18 sidecar: binds an explicit planning region to an EXISTING editor draft/version.
-- Requires content_draft migration. No alternate draft ID, source publication, default region or activation.
CREATE TABLE IF NOT EXISTS w18_content_draft_install_scope (
  draft_id BIGINT NOT NULL,
  draft_version BIGINT NOT NULL,
  owner_member_id BIGINT NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  region VARCHAR(128) NOT NULL,
  PRIMARY KEY(draft_id,draft_version),
  CONSTRAINT fk_w18_install_scope_content_draft FOREIGN KEY(draft_id) REFERENCES content_draft(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
-- CREATE IF NOT EXISTS does not repair an incompatible table; every scalar is mandatory.
-- runner: assert=scalar-true
SELECT ((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
  AND table_name='w18_content_draft_install_scope' AND table_type='BASE TABLE' AND engine='InnoDB' AND table_collation='utf8mb4_bin')=1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='w18_content_draft_install_scope')=5
  AND (SELECT COUNT(*) FROM (
    SELECT 'draft_id' AS column_name,'bigint' AS data_type,1 AS ordinal_position,NULL AS char_length,NULL AS collation_name
    UNION ALL SELECT 'draft_version','bigint',2,NULL,NULL
    UNION ALL SELECT 'owner_member_id','bigint',3,NULL,NULL
    UNION ALL SELECT 'payload_hash','char',4,64,'ascii_bin'
    UNION ALL SELECT 'region','varchar',5,128,'utf8mb4_bin'
  ) expected JOIN information_schema.columns actual ON actual.table_schema=DATABASE()
    AND actual.table_name='w18_content_draft_install_scope' AND actual.column_name=expected.column_name
    AND actual.ordinal_position=expected.ordinal_position AND actual.data_type=expected.data_type
    AND actual.is_nullable='NO' AND actual.column_default IS NULL AND actual.extra='' AND actual.generation_expression=''
    AND (actual.collation_name <=> expected.collation_name)
    AND (expected.char_length IS NULL OR actual.character_maximum_length=expected.char_length)
    AND (expected.data_type!='bigint' OR actual.column_type='bigint'))=5) AS migration_assertion;
-- runner: assert=scalar-true
SELECT ((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='w18_content_draft_install_scope' AND non_unique=0)=2
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='w18_content_draft_install_scope'
    AND index_name='PRIMARY' AND non_unique=0 AND sub_part IS NULL AND expression IS NULL AND is_visible='YES'
    AND index_type='BTREE' AND collation='A' AND ((column_name='draft_id' AND seq_in_index=1) OR (column_name='draft_version' AND seq_in_index=2)))=2
  AND (SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name='w18_content_draft_install_scope' AND referenced_table_name IS NOT NULL)=1
  AND (SELECT COUNT(*) FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r
    ON r.constraint_schema=k.constraint_schema AND r.table_name=k.table_name AND r.constraint_name=k.constraint_name
    WHERE k.table_schema=DATABASE() AND k.constraint_schema=DATABASE() AND k.table_name='w18_content_draft_install_scope'
    AND k.constraint_name='fk_w18_install_scope_content_draft' AND k.column_name='draft_id' AND k.ordinal_position=1 AND k.position_in_unique_constraint=1
    AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='content_draft' AND k.referenced_column_name='id'
    AND r.match_option='NONE' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION'))=1) AS migration_assertion;
