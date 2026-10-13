-- 刚到这座城·偏好题模板库发布链路：模板库保留 validationMethod=6 配置。
-- 前置：migration_20260820_new_city_preference_chain.sql。

SET @preference_library_json_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE()
       AND table_name='cms_template_library'
       AND column_name='preference_json');
SET @preference_library_json_sql := IF(@preference_library_json_exists=0,
    'ALTER TABLE cms_template_library ADD COLUMN preference_json JSON NULL COMMENT ''validationMethod=6 偏好题组配置'' AFTER question_option_media_json',
    'SELECT 1');
PREPARE preference_library_json_stmt FROM @preference_library_json_sql;
EXECUTE preference_library_json_stmt;
DEALLOCATE PREPARE preference_library_json_stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS migration_assertion
  FROM information_schema.columns
 WHERE table_schema=DATABASE()
   AND table_name='cms_template_library'
   AND column_name='preference_json'
   AND column_type='json'
   AND is_nullable='YES'
   AND column_default IS NULL
   AND extra='';

-- 回滚（有业务数据后须先导出，禁止盲删）：
-- ALTER TABLE cms_template_library DROP COLUMN preference_json;
