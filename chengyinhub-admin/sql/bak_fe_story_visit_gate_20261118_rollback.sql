-- 回滚 migration_20261118_fe_story_visit_gate.sql(仅在需要撤回自由探索到访解锁时手工执行)。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'cms_topic_node' AND column_name = 'visit_check') = 1,
    'ALTER TABLE cms_topic_node DROP COLUMN visit_check', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'cms_topic_node' AND column_name = 'visit_radius_m') = 1,
    'ALTER TABLE cms_topic_node DROP COLUMN visit_radius_m', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'player_node_progress' AND column_name = 'visit_method') = 1,
    'ALTER TABLE player_node_progress DROP COLUMN visit_method', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'player_node_progress' AND column_name = 'visit_time') = 1,
    'ALTER TABLE player_node_progress DROP COLUMN visit_time', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
