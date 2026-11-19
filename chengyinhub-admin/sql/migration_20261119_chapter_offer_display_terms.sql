-- 2026-11-19 FE-G5:自由探索承接供给的玩家可见条款。
-- merchant_chapter_offer 增三列(只做展示,不参与容量 / 核销 / 结算判定):
--   included          是否含在票内:1 含 / 0 不含(需另付) / NULL 未声明
--   extra_spend_text  另付说明,如「饮品另付」
--   reserve_required  是否需提前预约:1 需要 / 0 不需要
-- 套票冻结的 contract 快照(explore_bundle_order.snapshot_json)按 JSON 追加同名字段,无需 DDL。
-- 只新增可空 / 带默认值的列,可重复执行;表不存在时跳过(局部建表的测试库只叠它需要的表)。
-- 回滚:DROP 这三列即可,旧代码不读它们。生产执行须先只读预检并取得用户确认;本文件不连接生产数据库。

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer') = 1
           AND (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer' AND column_name = 'included') = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN included TINYINT NULL COMMENT ''是否含在票内 1含 0不含 NULL未声明''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer') = 1
           AND (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer' AND column_name = 'extra_spend_text') = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN extra_spend_text VARCHAR(100) NULL COMMENT ''另付说明，如「饮品另付」''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer') = 1
           AND (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer' AND column_name = 'reserve_required') = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN reserve_required TINYINT NOT NULL DEFAULT 0 COMMENT ''是否需提前预约 1需要 0不需要''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
