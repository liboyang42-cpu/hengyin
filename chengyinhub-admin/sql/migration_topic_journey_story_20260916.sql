-- R8-STORY:主题级剧情配置 journeyStory(开场通话/阶段结算/结局)落一列。
-- 与 journeyRules 同链路:create / update / edit-detail 回显 / info-to-user 玩家只读 /
-- 模板复制与重开一期沿用;NULL = 未配置,老主题行为完全不变。
-- 为什么是新列:它是主题级一份的叙事配置(阶段按现有章节、结局条件走路线图写法),
-- 与节点块配置(advancedConfigJson)分工不同,也与活动级 gameModuleConfigJson 不同源。
-- 生产执行必须走 chengyin-db-migrate;本文件只随代码交付,不自动落库。
-- 回滚(确认无数据依赖后):ALTER TABLE cms_topic DROP COLUMN journey_story_json;

SET @has_journey_story_json := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='cms_topic'
       AND column_name='journey_story_json');
SET @ddl := IF(@has_journey_story_json=0,
    'ALTER TABLE cms_topic ADD COLUMN journey_story_json JSON NULL COMMENT ''主题级剧情配置 journeyStory;NULL=未配置''',
    'SELECT ''cms_topic.journey_story_json already exists''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='cms_topic'
        AND column_name='journey_story_json'
        AND data_type='json' AND is_nullable='YES')=1
) AS journey_story_column_ready;
