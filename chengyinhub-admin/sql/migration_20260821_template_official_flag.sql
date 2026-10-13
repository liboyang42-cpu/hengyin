-- ============================================================================
-- 主题范本官方标识:cms_topic_template_meta 加 is_official 列
--
-- 背景:官方范本由平台官方账号创建,货架/详情需要一个「官方出品」标识位。
-- 表里此前无官方概念(cms_template_library.member_id NOT NULL,同样无)。
-- 运营用 SQL 手工标记官方范本 —— 本迁移**只加列,不 seed 任何数据**
-- (禁止硬编码假范本是产品拍板)。
--
-- ⚠️ 本文件只准备,不执行。落库走 chengyin-db-migrate 的只读预检 + 人工确认路径。
-- ⚠️ MySQL 8 无 ADD COLUMN IF NOT EXISTS,重复执行会报 Duplicate column,先跑预检。
-- ============================================================================

-- ======================= 第一步:只读预检 =======================
-- 1) 确认列尚不存在(有结果 = 已加过,别再执行第二步):
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME = 'cms_topic_template_meta'
--      AND COLUMN_NAME = 'is_official';
--
-- 2) 看一眼现有元数据行数(加列不影响存量,存量全部默认 0 = 非官方):
--   SELECT COUNT(*) FROM cms_topic_template_meta WHERE del_flag = 0;

-- ======================= 第二步:DDL =======================

ALTER TABLE cms_topic_template_meta
    ADD COLUMN is_official tinyint NOT NULL DEFAULT 0
        COMMENT '1=官方范本(平台官方账号出品,运营用SQL标记)' AFTER author;

-- ======================= 第三步:执行后回读 =======================
-- 预期一行,COLUMN_DEFAULT='0':
--   SELECT COLUMN_NAME, COLUMN_DEFAULT, IS_NULLABLE FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME = 'cms_topic_template_meta'
--      AND COLUMN_NAME = 'is_official';
