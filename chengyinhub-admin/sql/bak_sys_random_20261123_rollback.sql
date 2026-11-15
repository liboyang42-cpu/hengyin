-- ============================================================================
-- Rollback for migration_20261123_sys_random.sql
-- ============================================================================

DELETE FROM sys_menu WHERE component IN ('business/rewarddrop/index', 'business/nodetaskpool/index');
DROP TABLE IF EXISTS node_task_dispatch;
DROP TABLE IF EXISTS node_task_pool_item;
DROP TABLE IF EXISTS node_task_pool;
DROP TABLE IF EXISTS game_random_audit;
