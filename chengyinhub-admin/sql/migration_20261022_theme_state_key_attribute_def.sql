-- F2 RPG P0 / T01:属性定义扩展。theme_state_key 补 9 列(范围、展示、颜色、图标、排序、可见、持久化作用域、规则)。
-- 依赖:migration_theme_state_key_20260916.sql(建表)。
-- 只补结构:9 列全部 NULL 且无回填,NULL 即「老口径」—— 范围按 0..100、展示 number、每局重置、展示在角色卡。
-- 已有正确列可重放,部分完成可续跑;已有同名但形态不对的列在任何 DDL 前 fail closed。
-- 部署顺序:先完成迁移验收,再部署读这些列的 jar(Mapper 的 select 列表含新列)。
-- 生产执行须走 chengyin-db-migrate 只读预检并取得用户确认;本文件不连接生产数据库。

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'theme_state_key'
      AND column_name IN ('min_value','max_value','display_type','color','icon','sort_order','visible','persist_scope','rules_json')
      AND NOT (is_nullable = 'YES' AND column_default IS NULL
        AND ((column_name IN ('min_value','max_value','sort_order') AND data_type = 'int')
          OR (column_name = 'visible' AND data_type = 'tinyint')
          OR (column_name IN ('display_type','color','persist_scope') AND data_type = 'varchar' AND character_maximum_length = 16)
          OR (column_name = 'icon' AND data_type = 'varchar' AND character_maximum_length = 32)
          OR (column_name = 'rules_json' AND data_type = 'varchar' AND character_maximum_length = 1024)))) = 0,
  1, JSON_EXTRACT('ASSERT_FAILED__theme_state_key_attribute_def_before_ddl', '$')
) AS UNSIGNED) AS assertion_ok;

SET @tsk_min_value_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'min_value'
);
SET @tsk_min_value_sql := IF(
    @tsk_min_value_exists = 0,
    'ALTER TABLE theme_state_key ADD COLUMN min_value INT NULL DEFAULT NULL COMMENT ''属性下限;NULL=默认 0(flag 恒为 0)'', ALGORITHM=INSTANT',
    'SELECT ''theme_state_key.min_value already exists'''
);
PREPARE tsk_min_value_stmt FROM @tsk_min_value_sql;
EXECUTE tsk_min_value_stmt;
DEALLOCATE PREPARE tsk_min_value_stmt;

SET @tsk_max_value_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'max_value'
);
SET @tsk_max_value_sql := IF(
    @tsk_max_value_exists = 0,
    'ALTER TABLE theme_state_key ADD COLUMN max_value INT NULL DEFAULT NULL COMMENT ''属性上限;NULL=默认 100(flag 恒为 1)'', ALGORITHM=INSTANT',
    'SELECT ''theme_state_key.max_value already exists'''
);
PREPARE tsk_max_value_stmt FROM @tsk_max_value_sql;
EXECUTE tsk_max_value_stmt;
DEALLOCATE PREPARE tsk_max_value_stmt;

SET @tsk_display_type_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'display_type'
);
SET @tsk_display_type_sql := IF(
    @tsk_display_type_exists = 0,
    'ALTER TABLE theme_state_key ADD COLUMN display_type VARCHAR(16) NULL DEFAULT NULL COMMENT ''角色卡展示:number/bar/stars;NULL=number'', ALGORITHM=INSTANT',
    'SELECT ''theme_state_key.display_type already exists'''
);
PREPARE tsk_display_type_stmt FROM @tsk_display_type_sql;
EXECUTE tsk_display_type_stmt;
DEALLOCATE PREPARE tsk_display_type_stmt;

SET @tsk_color_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'color'
);
SET @tsk_color_sql := IF(
    @tsk_color_exists = 0,
    'ALTER TABLE theme_state_key ADD COLUMN color VARCHAR(16) NULL DEFAULT NULL COMMENT ''角色卡色值 #RRGGBB;NULL=客户端默认'', ALGORITHM=INSTANT',
    'SELECT ''theme_state_key.color already exists'''
);
PREPARE tsk_color_stmt FROM @tsk_color_sql;
EXECUTE tsk_color_stmt;
DEALLOCATE PREPARE tsk_color_stmt;

SET @tsk_icon_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'icon'
);
SET @tsk_icon_sql := IF(
    @tsk_icon_exists = 0,
    'ALTER TABLE theme_state_key ADD COLUMN icon VARCHAR(32) NULL DEFAULT NULL COMMENT ''角色卡图标键(小写字母数字短横线,不是 URL)'', ALGORITHM=INSTANT',
    'SELECT ''theme_state_key.icon already exists'''
);
PREPARE tsk_icon_stmt FROM @tsk_icon_sql;
EXECUTE tsk_icon_stmt;
DEALLOCATE PREPARE tsk_icon_stmt;

SET @tsk_sort_order_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'sort_order'
);
SET @tsk_sort_order_sql := IF(
    @tsk_sort_order_exists = 0,
    'ALTER TABLE theme_state_key ADD COLUMN sort_order INT NULL DEFAULT NULL COMMENT ''角色卡排序,升序;NULL 排最后'', ALGORITHM=INSTANT',
    'SELECT ''theme_state_key.sort_order already exists'''
);
PREPARE tsk_sort_order_stmt FROM @tsk_sort_order_sql;
EXECUTE tsk_sort_order_stmt;
DEALLOCATE PREPARE tsk_sort_order_stmt;

SET @tsk_visible_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'visible'
);
SET @tsk_visible_sql := IF(
    @tsk_visible_exists = 0,
    'ALTER TABLE theme_state_key ADD COLUMN visible TINYINT(1) NULL DEFAULT NULL COMMENT ''角色卡是否展示;NULL=展示'', ALGORITHM=INSTANT',
    'SELECT ''theme_state_key.visible already exists'''
);
PREPARE tsk_visible_stmt FROM @tsk_visible_sql;
EXECUTE tsk_visible_stmt;
DEALLOCATE PREPARE tsk_visible_stmt;

SET @tsk_persist_scope_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'persist_scope'
);
SET @tsk_persist_scope_sql := IF(
    @tsk_persist_scope_exists = 0,
    'ALTER TABLE theme_state_key ADD COLUMN persist_scope VARCHAR(16) NULL DEFAULT NULL COMMENT ''run=每局重置/topic=跨局保留;NULL=run'', ALGORITHM=INSTANT',
    'SELECT ''theme_state_key.persist_scope already exists'''
);
PREPARE tsk_persist_scope_stmt FROM @tsk_persist_scope_sql;
EXECUTE tsk_persist_scope_stmt;
DEALLOCATE PREPARE tsk_persist_scope_stmt;

SET @tsk_rules_json_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'rules_json'
);
SET @tsk_rules_json_sql := IF(
    @tsk_rules_json_exists = 0,
    'ALTER TABLE theme_state_key ADD COLUMN rules_json VARCHAR(1024) NULL DEFAULT NULL COMMENT ''展示规则 JSON(levels 档位);NULL=无'', ALGORITHM=INSTANT',
    'SELECT ''theme_state_key.rules_json already exists'''
);
PREPARE tsk_rules_json_stmt FROM @tsk_rules_json_sql;
EXECUTE tsk_rules_json_stmt;
DEALLOCATE PREPARE tsk_rules_json_stmt;

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'theme_state_key'
      AND column_name IN ('min_value','max_value','display_type','color','icon','sort_order','visible','persist_scope','rules_json')
      AND is_nullable = 'YES' AND column_default IS NULL) = 9,
  1, JSON_EXTRACT('ASSERT_FAILED__theme_state_key_attribute_def_after_ddl', '$')
) AS UNSIGNED) AS assertion_ok;

-- 回滚(新列有作者数据后必须先导出,禁止盲删):
-- ALTER TABLE theme_state_key DROP COLUMN rules_json, DROP COLUMN persist_scope, DROP COLUMN visible,
--   DROP COLUMN sort_order, DROP COLUMN icon, DROP COLUMN color, DROP COLUMN display_type,
--   DROP COLUMN max_value, DROP COLUMN min_value;
