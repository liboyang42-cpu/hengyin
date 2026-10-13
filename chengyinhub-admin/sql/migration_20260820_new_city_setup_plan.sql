-- 刚到这座城·新生活装机计划 P1：完成规则地基。
-- ③ 生产执行必须走 chengyin-db-migrate；本文件只准备，不在代码任务中直连生产库。

-- 只读预检：两列均应不存在。
-- SELECT table_name, column_name FROM information_schema.columns
--  WHERE table_schema = DATABASE() AND (
--    (table_name='cms_topic' AND column_name='complete_rule_json') OR
--    (table_name='cms_topic_chapter' AND column_name='need_group'));

SET @complete_rule_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='complete_rule_json');
SET @complete_rule_sql := IF(@complete_rule_exists=0,
    'ALTER TABLE cms_topic ADD COLUMN complete_rule_json JSON NULL COMMENT ''自由探索完成规则;NULL=必选章全核销'' AFTER product_type',
    'SELECT 1');
PREPARE complete_rule_stmt FROM @complete_rule_sql;
EXECUTE complete_rule_stmt;
DEALLOCATE PREPARE complete_rule_stmt;

SET @need_group_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='cms_topic_chapter' AND column_name='need_group');
SET @need_group_sql := IF(@need_group_exists=0,
    'ALTER TABLE cms_topic_chapter ADD COLUMN need_group TINYINT NULL COMMENT ''需求组:1生存效率 2自我安顿 3城市归属;NULL不参与分组规则'' AFTER required',
    'SELECT 1');
PREPARE need_group_stmt FROM @need_group_sql;
EXECUTE need_group_stmt;
DEALLOCATE PREPARE need_group_stmt;

-- 回读合同：两列各 1；complete_rule_json 必须真是 json。
-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='cms_topic'
        AND column_name='complete_rule_json' AND data_type='json' AND is_nullable='YES') = 1
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='cms_topic_chapter'
        AND column_name='need_group' AND data_type='tinyint' AND is_nullable='YES') = 1
) AS migration_assertion;

-- 回滚（有业务数据后须先导出，禁止盲删）：
-- ALTER TABLE cms_topic_chapter DROP COLUMN need_group;
-- ALTER TABLE cms_topic DROP COLUMN complete_rule_json;
