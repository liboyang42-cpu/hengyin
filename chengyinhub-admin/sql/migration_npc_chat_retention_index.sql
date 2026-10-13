-- P0 合规:NPC 追问日志留存清理索引(《隐私政策》§6.4 数据留存条款配套)。
-- npc_chat_log 存聊天原文(user_msg/npc_reply),由 dataRetentionCleanupTask 按 create_time 分批删;
--   现有索引仅 idx_user_activity(user_id,activity_id)/idx_safety(safety_flag),create_time 无独立索引
--   → 每批 LIMIT 删都全表扫;加 create_time 索引让留存删除走范围扫描(表随聊天量增长后尤为关键)。
-- 幂等:索引存在则跳过,可重复执行。用 prepared-statement 守卫(兼容 run_migration.py 按 ; 切分;不用 DELIMITER 存储过程)。
-- ★执行顺序:纯性能优化、不影响正确性(create_time 由 insert 硬填 NOW() 永不为 NULL),随 schema 迁移一批执行即可(jar 部署前后均可)。
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE table_schema = DATABASE() AND table_name = 'npc_chat_log' AND index_name = 'idx_npc_chat_create_time');
SET @sql := IF(@has_idx = 0,
    'ALTER TABLE npc_chat_log ADD INDEX idx_npc_chat_create_time (create_time)',
    'SELECT ''idx_npc_chat_create_time 已存在,跳过''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
