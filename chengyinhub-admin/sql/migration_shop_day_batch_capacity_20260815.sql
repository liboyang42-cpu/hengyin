-- 探店日 2-5：章节人工批次容量。
-- 只新增 batch_size / batch_interval_minutes；effort_max_concurrent 为既有列。
-- 本文件【可重跑】(2026-08-18 改)：两列与 ck_chapter_meta_service_capacity 各自带
-- information_schema 预检，已存在即跳过。落库中断后直接重跑即可,不再需要人工判 NO-GO。
-- 应用 upsert 会写 del_flag = 0：若同章元数据行曾被软删，会连同该行既有展示字段一起复活；
-- 这是本次保留的显式既有语义，不在 2-5 内改业务逻辑。
--
-- 落库前只读预检（由总控另走 chengyin-db-migrate；本任务严禁连接或执行数据库）：
-- SELECT column_name, data_type, is_nullable
--   FROM information_schema.columns
--  WHERE table_schema = database()
--    AND table_name = 'cms_topic_chapter_template_meta'
--    AND column_name IN ('batch_size', 'batch_interval_minutes', 'effort_max_concurrent');
-- SELECT index_name, non_unique, column_name
--   FROM information_schema.statistics
--  WHERE table_schema = database()
--    AND table_name = 'cms_topic_chapter_template_meta'
--    AND index_name = 'uk_chapter_meta_chapter';
-- SELECT constraint_name
--   FROM information_schema.table_constraints
--  WHERE constraint_schema = database()
--    AND constraint_name = 'ck_chapter_meta_service_capacity';

-- 【幂等】2026-08-18：原本是一条裸 ALTER(两列 + 一个 CHECK),重跑报 3822
-- Duplicate check constraint name —— 落库中断后无法恢复。上面那句「不可重跑、任一已存在
-- 都保持 NO-GO」正是这个病:它把「中断了怎么办」推给了人。改成本仓既有范式:
-- information_schema 预检 + PREPARE,三个动作各自独立幂等。
-- ⚠️ MySQL 8 不支持 ADD COLUMN IF NOT EXISTS(报 1064),只能这么写。
-- ⚠️ 顺序不能反:CHECK 引用了这两列,必须两列都在之后再建。
-- 没跑过的库上跑一遍的效果与改前完全一致(列定义/位置/CHECK 表达式逐字未动)。
SET @chapter_batch_size_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter_template_meta'
      AND column_name = 'batch_size'
);
SET @chapter_batch_size_sql := IF(
    @chapter_batch_size_exists = 0,
    'ALTER TABLE cms_topic_chapter_template_meta ADD COLUMN batch_size INT NULL COMMENT ''人工批次每批人数'' AFTER effort_materials',
    'SELECT 1'
);
PREPARE chapter_batch_size_stmt FROM @chapter_batch_size_sql;
EXECUTE chapter_batch_size_stmt;
DEALLOCATE PREPARE chapter_batch_size_stmt;

SET @chapter_batch_interval_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter_template_meta'
      AND column_name = 'batch_interval_minutes'
);
SET @chapter_batch_interval_sql := IF(
    @chapter_batch_interval_exists = 0,
    'ALTER TABLE cms_topic_chapter_template_meta ADD COLUMN batch_interval_minutes INT NULL COMMENT ''人工批次间隔(分钟)'' AFTER batch_size',
    'SELECT 1'
);
PREPARE chapter_batch_interval_stmt FROM @chapter_batch_interval_sql;
EXECUTE chapter_batch_interval_stmt;
DEALLOCATE PREPARE chapter_batch_interval_stmt;

SET @chapter_capacity_check_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'cms_topic_chapter_template_meta'
      AND constraint_name = 'ck_chapter_meta_service_capacity' AND constraint_type = 'CHECK'
);
SET @chapter_capacity_check_sql := IF(
    @chapter_capacity_check_exists = 0,
    'ALTER TABLE cms_topic_chapter_template_meta ADD CONSTRAINT ck_chapter_meta_service_capacity CHECK ((batch_size IS NULL AND batch_interval_minutes IS NULL) OR (batch_size IS NOT NULL AND batch_size > 0 AND batch_interval_minutes IS NOT NULL AND batch_interval_minutes > 0 AND effort_max_concurrent IS NOT NULL AND effort_max_concurrent > 0))',
    'SELECT 1'
);
PREPARE chapter_capacity_check_stmt FROM @chapter_capacity_check_sql;
EXECUTE chapter_capacity_check_stmt;
DEALLOCATE PREPARE chapter_capacity_check_stmt;

-- 落库后必须换只读连接回读 information_schema：两列存在、effort_max_concurrent 仍在、
-- uk_chapter_meta_chapter(chapter_id) 仍唯一、CHECK 处于 ENFORCED。执行回执不能代替回读。
