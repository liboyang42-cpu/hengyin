-- 合规(P0-10):AI 生成日志留存清理索引。
-- ai_generation_log 由 dataRetentionCleanupTask 按 create_time 分批删(chengyin.retention.ai-generation-days,默认 180 天);
--   现有索引只有 idx_ai_gen_member(member_id,create_time)/idx_ai_gen_biz/idx_ai_gen_trace,按 create_time 范围删会全表扫。
-- 幂等:索引存在则跳过,可重复执行。用 prepared-statement 守卫(兼容 run_migration.py 按 ; 切分)。
-- 执行顺序:纯性能优化、不影响正确性,jar 部署前后均可。
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE table_schema = DATABASE() AND table_name = 'ai_generation_log' AND index_name = 'idx_ai_gen_create_time');
SET @sql := IF(@has_idx = 0,
    'ALTER TABLE ai_generation_log ADD INDEX idx_ai_gen_create_time (create_time)',
    'SELECT ''idx_ai_gen_create_time 已存在,跳过''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
