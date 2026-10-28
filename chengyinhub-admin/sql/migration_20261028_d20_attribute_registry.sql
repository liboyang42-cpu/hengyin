-- 文档二 UI 实施 L8:d20 属性登记(A1 / A2 / A3)。
-- 1) theme_state_key 补 is_attribute(「是属性」单独存,A2)与 attribute_preset(属性类型预设,A3)。
-- 2) A1:老主题 cms_topic.journey_rules_json 里 attributes 的 counter.* 行(编辑器原「自定义整数值」)
--    登记进注册表并标成属性;进度钟(rpg.clocks[].var)用的键不是属性,跳过。
--    已注册的同名键只补 is_attribute / attribute_preset,不改它的 label、范围与初值。
-- 依赖:migration_theme_state_key_20260916.sql(建表)、migration_20261022_theme_state_key_attribute_def.sql(范围列)、
--       migration_topic_journey_rules_20260916.sql(journey_rules_json)。
-- 可重复执行:列已存在时跳过 DDL;回填用 INSERT ... ON DUPLICATE KEY UPDATE,结果幂等。
-- 部署顺序:先落库,再部署读这两列的 jar。生产执行须走 chengyin-db-migrate 只读预检并取得用户确认。

SET @tsk_is_attribute_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'is_attribute'
);
SET @tsk_is_attribute_sql := IF(
    @tsk_is_attribute_exists = 0,
    'ALTER TABLE theme_state_key ADD COLUMN is_attribute TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''1=d20 属性(A2)'', ALGORITHM=INSTANT',
    'SELECT ''theme_state_key.is_attribute already exists'''
);
PREPARE tsk_is_attribute_stmt FROM @tsk_is_attribute_sql;
EXECUTE tsk_is_attribute_stmt;
DEALLOCATE PREPARE tsk_is_attribute_stmt;

SET @tsk_attribute_preset_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'attribute_preset'
);
SET @tsk_attribute_preset_sql := IF(
    @tsk_attribute_preset_exists = 0,
    'ALTER TABLE theme_state_key ADD COLUMN attribute_preset VARCHAR(16) NULL DEFAULT NULL COMMENT ''属性类型预设 observe/nerve/body/insight/agility/charm/fortune/custom(A3)'', ALGORITHM=INSTANT',
    'SELECT ''theme_state_key.attribute_preset already exists'''
);
PREPARE tsk_attribute_preset_stmt FROM @tsk_attribute_preset_sql;
EXECUTE tsk_attribute_preset_stmt;
DEALLOCATE PREPARE tsk_attribute_preset_stmt;

INSERT INTO theme_state_key
    (topic_id, state_key, key_type, scope, label, initial_value, min_value, max_value,
     display_type, visible, persist_scope, is_attribute, attribute_preset, create_time, update_time)
SELECT t.id, a.state_key, 'counter', 'run', LEFT(a.label, 128), a.initial_value, a.min_value, a.max_value,
       'number', IF(a.visible_flag = 'false', 0, 1), 'run', 1, 'custom', NOW(), NOW()
  FROM cms_topic t
  JOIN JSON_TABLE(t.journey_rules_json, '$.attributes[*]' COLUMNS (
         state_key     VARCHAR(64)  PATH '$.key',
         label         VARCHAR(255) PATH '$.label',
         initial_value INT          PATH '$.initial' DEFAULT '0' ON EMPTY NULL ON ERROR,
         min_value     INT          PATH '$.min' NULL ON EMPTY NULL ON ERROR,
         max_value     INT          PATH '$.max' NULL ON EMPTY NULL ON ERROR,
         visible_flag  VARCHAR(8)   PATH '$.visible'
       )) a
 WHERE t.journey_rules_json IS NOT NULL
   AND JSON_VALID(t.journey_rules_json)
   AND a.state_key REGEXP '^counter\\.[a-z][a-z0-9_]{0,47}$'
   AND a.label IS NOT NULL AND TRIM(a.label) <> ''
   AND (JSON_SEARCH(t.journey_rules_json, 'one', a.state_key, NULL, '$.rpg.clocks[*].var') IS NULL)
ON DUPLICATE KEY UPDATE
   is_attribute = 1,
   attribute_preset = COALESCE(theme_state_key.attribute_preset, 'custom');
