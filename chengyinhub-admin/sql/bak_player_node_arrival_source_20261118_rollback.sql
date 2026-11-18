-- 回滚 migration_20261118_player_node_arrival_source.sql(仅在需要撤回 F08 时手工执行)。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'player_node_progress'
                 AND column_name = 'arrival_source') = 1,
    'ALTER TABLE player_node_progress DROP COLUMN arrival_source',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
