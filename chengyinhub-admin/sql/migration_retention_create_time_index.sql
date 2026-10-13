-- P0 合规:留存清理索引(《隐私政策》§6.4 配套)。
-- biz_event_log 按 create_time 分批删,但其现有索引全部以 event_name/member_id/biz_type 为前导列,create_time 无独立索引
--   → 每批 LIMIT 删都全表扫;加 create_time 索引让留存删除走范围扫描(表随时间增长后尤为关键)。
-- tms_browse_record 留存已改锚 browse_time,复用其现有 idx_browse_time(browse_time),无需再加索引。
-- 幂等:索引存在则跳过,可重复执行。用 prepared-statement 守卫(兼容 run_migration.py 按 ; 切分;不用 DELIMITER 存储过程)。
-- ★执行顺序:纯性能优化、不影响正确性,随 schema 迁移一批执行即可(jar 部署前后均可)。
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE table_schema = DATABASE() AND table_name = 'biz_event_log' AND index_name = 'idx_biz_event_create_time');
SET @sql := IF(@has_idx = 0,
    'ALTER TABLE biz_event_log ADD INDEX idx_biz_event_create_time (create_time)',
    'SELECT ''idx_biz_event_create_time 已存在,跳过''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
