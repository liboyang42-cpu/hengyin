-- MySQL requires CHECK constraint names to be unique within a schema.
-- Keep the perk rule unchanged and rename it before marketing_promotion_redemption is created.
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema=DATABASE() AND table_name='redemption')=1
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
   WHERE constraint_schema=DATABASE() AND table_name='redemption'
     AND constraint_type='CHECK'
     AND constraint_name IN ('ck_redemption_status','ck_perk_redemption_status'))=1
  AND (SELECT COUNT(*) FROM information_schema.check_constraints cc
   JOIN information_schema.table_constraints tc
     ON tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name
   WHERE cc.constraint_schema=DATABASE() AND tc.table_name='redemption'
     AND tc.constraint_type='CHECK'
     AND cc.check_clause LIKE '%ACTIVE%' AND cc.check_clause LIKE '%REVERSED%')=1;

SET @perk_check_rename_sql = IF(
  (SELECT COUNT(*) FROM information_schema.table_constraints
   WHERE constraint_schema=DATABASE() AND table_name='redemption'
     AND constraint_type='CHECK' AND constraint_name='ck_redemption_status')=1,
  'ALTER TABLE redemption DROP CHECK ck_redemption_status, ADD CONSTRAINT ck_perk_redemption_status CHECK (status IN (''ACTIVE'', ''REVERSED''))',
  'SELECT 1'
);
PREPARE perk_check_rename_stmt FROM @perk_check_rename_sql;
EXECUTE perk_check_rename_stmt;
DEALLOCATE PREPARE perk_check_rename_stmt;

-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.check_constraints cc
JOIN information_schema.table_constraints tc
  ON tc.constraint_schema=cc.constraint_schema
  AND tc.constraint_name=cc.constraint_name
WHERE cc.constraint_schema=DATABASE() AND tc.table_name='redemption'
  AND tc.constraint_type='CHECK' AND tc.constraint_name='ck_perk_redemption_status'
  AND cc.check_clause LIKE '%ACTIVE%' AND cc.check_clause LIKE '%REVERSED%';
