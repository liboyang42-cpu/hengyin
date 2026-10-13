-- R2-B10:主题数值规则 journeyRules(HP/幸运/难度 DC/恢复)落一列。
-- 主题级一份,专业主题配置里填;NULL = 未启用,老主题行为完全不变。
-- 为什么是新列而不是塞进 game_module_config_json:后者是「三身份活动级游戏模组定义」
-- (开局准备时冻结到 game_session 的运行时配置),没有主题编辑保存入口、也没有校验入口;
-- journeyRules 要跟 create/update/edit-detail 同链路,需要一个独立、可校验可回显的容器。
-- 生产执行必须走 chengyin-db-migrate;本文件只随代码交付,不自动落库。
-- 回滚(确认无数据依赖后):ALTER TABLE cms_topic DROP COLUMN journey_rules_json;

SET @has_journey_rules_json := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='cms_topic'
       AND column_name='journey_rules_json');
SET @ddl := IF(@has_journey_rules_json=0,
    'ALTER TABLE cms_topic ADD COLUMN journey_rules_json JSON NULL COMMENT ''主题数值规则 journeyRules;NULL=未启用''',
    'SELECT ''cms_topic.journey_rules_json already exists''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='cms_topic'
        AND column_name='journey_rules_json'
        AND data_type='json' AND is_nullable='YES')=1
) AS journey_rules_column_ready;
