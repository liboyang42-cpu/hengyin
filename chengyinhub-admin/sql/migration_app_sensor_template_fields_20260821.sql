-- App 专属传感器玩法(vm=7)模板字段。先落库，再部署读取新列的 jar。
-- 本迁移仅扩展 cms_member_template / cms_template_library；旧行两列均 NULL，旧玩法语义不变。
-- DDL 会隐式提交，禁止用 --transaction；生产执行必须走 chengyin-db-migrate 的预检/执行/回读链。

SET @member_sensor_type_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'cms_member_template' AND column_name = 'sensor_type'
);
SET @member_sensor_type_sql := IF(@member_sensor_type_exists = 0,
  'ALTER TABLE cms_member_template ADD COLUMN sensor_type VARCHAR(32) NULL COMMENT ''vm=7传感器类型:still/steps/audio_clip'' AFTER validation_method',
  'SELECT 1');
PREPARE member_sensor_type_stmt FROM @member_sensor_type_sql;
EXECUTE member_sensor_type_stmt;
DEALLOCATE PREPARE member_sensor_type_stmt;

SET @member_sensor_config_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'cms_member_template' AND column_name = 'sensor_config'
);
SET @member_sensor_config_sql := IF(@member_sensor_config_exists = 0,
  'ALTER TABLE cms_member_template ADD COLUMN sensor_config JSON NULL COMMENT ''vm=7传感器玩法参数'' AFTER sensor_type',
  'SELECT 1');
PREPARE member_sensor_config_stmt FROM @member_sensor_config_sql;
EXECUTE member_sensor_config_stmt;
DEALLOCATE PREPARE member_sensor_config_stmt;

SET @library_sensor_type_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'cms_template_library' AND column_name = 'sensor_type'
);
SET @library_sensor_type_sql := IF(@library_sensor_type_exists = 0,
  'ALTER TABLE cms_template_library ADD COLUMN sensor_type VARCHAR(32) NULL COMMENT ''vm=7传感器类型:still/steps/audio_clip'' AFTER validation_method',
  'SELECT 1');
PREPARE library_sensor_type_stmt FROM @library_sensor_type_sql;
EXECUTE library_sensor_type_stmt;
DEALLOCATE PREPARE library_sensor_type_stmt;

SET @library_sensor_config_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'cms_template_library' AND column_name = 'sensor_config'
);
SET @library_sensor_config_sql := IF(@library_sensor_config_exists = 0,
  'ALTER TABLE cms_template_library ADD COLUMN sensor_config JSON NULL COMMENT ''vm=7传感器玩法参数'' AFTER sensor_type',
  'SELECT 1');
PREPARE library_sensor_config_stmt FROM @library_sensor_config_sql;
EXECUTE library_sensor_config_stmt;
DEALLOCATE PREPARE library_sensor_config_stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 2
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'cms_member_template'
  AND ((column_name = 'sensor_type' AND data_type = 'varchar' AND character_maximum_length = 32 AND is_nullable = 'YES')
    OR (column_name = 'sensor_config' AND data_type = 'json' AND is_nullable = 'YES'));

-- runner: assert=scalar-true
SELECT COUNT(*) = 2
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'cms_template_library'
  AND ((column_name = 'sensor_type' AND data_type = 'varchar' AND character_maximum_length = 32 AND is_nullable = 'YES')
    OR (column_name = 'sensor_config' AND data_type = 'json' AND is_nullable = 'YES'));

-- 回滚(仅确认无 vm=7 数据后人工执行):
-- ALTER TABLE cms_member_template DROP COLUMN sensor_config, DROP COLUMN sensor_type;
-- ALTER TABLE cms_template_library DROP COLUMN sensor_config, DROP COLUMN sensor_type;
