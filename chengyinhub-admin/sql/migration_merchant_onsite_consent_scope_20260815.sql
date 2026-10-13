-- 探店日一店一次明示授权 scope；仅准备 migration，本分支不执行。
-- 生产执行前必须走 chengyin-db-migrate 只读预检、用户确认与执行后 schema readback。

SET @consent_scope_type_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE table_schema = DATABASE() AND table_name = 'consent_record'
      AND column_name = 'scope_type'
);
SET @consent_scope_type_ddl := IF(
    @consent_scope_type_exists = 0,
    'ALTER TABLE consent_record ADD COLUMN scope_type VARCHAR(32) NULL COMMENT ''授权对象类型；探店日只允许 MERCHANT'' AFTER scene, ALGORITHM=INSTANT',
    'SELECT ''consent_record.scope_type already exists, skip'''
);
PREPARE consent_scope_type_stmt FROM @consent_scope_type_ddl;
EXECUTE consent_scope_type_stmt;
DEALLOCATE PREPARE consent_scope_type_stmt;

SET @consent_scope_id_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE table_schema = DATABASE() AND table_name = 'consent_record'
      AND column_name = 'scope_id'
);
SET @consent_scope_id_ddl := IF(
    @consent_scope_id_exists = 0,
    'ALTER TABLE consent_record ADD COLUMN scope_id BIGINT NULL COMMENT ''授权对象 ID；MERCHANT 时为商家 member_id'' AFTER scope_type, ALGORITHM=INSTANT',
    'SELECT ''consent_record.scope_id already exists, skip'''
);
PREPARE consent_scope_id_stmt FROM @consent_scope_id_ddl;
EXECUTE consent_scope_id_stmt;
DEALLOCATE PREPARE consent_scope_id_stmt;

SET @consent_scope_index_shape := (
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.STATISTICS
    WHERE table_schema = DATABASE() AND table_name = 'consent_record'
      AND index_name = 'idx_consent_member_doc_scene_scope'
);
SET @consent_scope_index_ddl := IF(
    @consent_scope_index_shape IS NULL
      OR @consent_scope_index_shape <> 'member_id,doc_type,scene,scope_type,scope_id,occurred_at',
    'ALTER TABLE consent_record ADD KEY idx_consent_member_doc_scene_scope (member_id, doc_type, scene, scope_type, scope_id, occurred_at)',
    'SELECT ''idx_consent_member_doc_scene_scope already exists, skip'''
);
PREPARE consent_scope_index_stmt FROM @consent_scope_index_ddl;
EXECUTE consent_scope_index_stmt;
DEALLOCATE PREPARE consent_scope_index_stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 2
FROM information_schema.COLUMNS
WHERE table_schema = DATABASE() AND table_name = 'consent_record'
  AND column_name IN ('scope_type', 'scope_id');

-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM (
    SELECT index_name
    FROM information_schema.STATISTICS
    WHERE table_schema = DATABASE() AND table_name = 'consent_record'
      AND index_name = 'idx_consent_member_doc_scene_scope'
    GROUP BY index_name
    HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') =
      'member_id,doc_type,scene,scope_type,scope_id,occurred_at'
) verified_consent_scope_index;
