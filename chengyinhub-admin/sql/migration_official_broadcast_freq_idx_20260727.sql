-- P2-17 官方通知频控查询覆盖索引。
-- 查询条件:member_id + channel + reached + create_time;先筛资格再按时间窗口统计。
-- 幂等:MySQL 8 用 information_schema 预检 + PREPARE,不使用 MariaDB 专属语法。
-- 生产执行前必须用 chengyin-db-migrate 做 --check/--describe,执行后立即回读索引列序。

SET @p2_17_idx_exists := (SELECT COUNT(*)
                          FROM information_schema.statistics
                          WHERE table_schema = DATABASE()
                            AND table_name = 'official_broadcast_log'
                            AND index_name = 'idx_obl_member_channel_reached_time');
SET @p2_17_sql := IF(@p2_17_idx_exists = 0,
  'ALTER TABLE official_broadcast_log ADD INDEX idx_obl_member_channel_reached_time (member_id, channel, reached, create_time)',
  'SELECT ''idx_obl_member_channel_reached_time already exists, skip''');
PREPARE p2_17_stmt FROM @p2_17_sql;
EXECUTE p2_17_stmt;
DEALLOCATE PREPARE p2_17_stmt;
