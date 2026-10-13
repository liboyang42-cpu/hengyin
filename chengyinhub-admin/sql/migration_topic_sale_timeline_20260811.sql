-- 主题期次与时间真源（合同一/二，2026-08-11）
--
-- 本文件只随代码提交，绝不由本任务执行。生产执行前必须走 chengyin-db-migrate 的只读预检与人工确认。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS；本迁移故意使用普通 ADD，重复执行会报 1060。
--
-- 只读预检（先单独执行；三列或两个索引任一已存在即停止，勿直接重跑本文件）：
-- SELECT column_name, column_type, is_nullable
--   FROM information_schema.columns
--  WHERE table_schema = DATABASE()
--    AND table_name = 'cms_topic'
--    AND column_name IN ('sale_end_at', 'selling_started_at', 'source_topic_id');
-- SELECT index_name, column_name, seq_in_index
--   FROM information_schema.statistics
--  WHERE table_schema = DATABASE()
--    AND table_name = 'cms_topic'
--    AND index_name IN ('idx_cms_topic_lifecycle_sale_end', 'idx_cms_topic_source_topic_id')
--  ORDER BY index_name, seq_in_index;

ALTER TABLE cms_topic
    ADD COLUMN sale_end_at DATETIME NULL COMMENT '本期所有可售SKU的整期硬售止（排他上界）' AFTER recruit_deadline,
    ADD COLUMN selling_started_at DATETIME NULL COMMENT 'PRICING→SELLING首次CAS成功的实际开售时刻' AFTER sale_end_at,
    ADD COLUMN source_topic_id BIGINT NULL COMMENT 'REOPEN_NEXT_EDITION来源cms_topic.id；不得使用历史topic_id' AFTER topic_id,
    ADD INDEX idx_cms_topic_lifecycle_sale_end (lifecycle, sale_end_at),
    ADD INDEX idx_cms_topic_source_topic_id (source_topic_id);

-- 存量回填只读清单：没有可信历史值的行必须保持 NULL 并进入人工逐期确认；
-- 禁止从 update_time、ticket.start_time、ticket.end_time 或 signup_deadline 猜测。
-- SELECT id, name, lifecycle, sale_end_at, selling_started_at, source_topic_id
--   FROM cms_topic
--  WHERE del_flag = 0
--    AND lifecycle >= 3
--    AND (sale_end_at IS NULL OR selling_started_at IS NULL)
--  ORDER BY id;
