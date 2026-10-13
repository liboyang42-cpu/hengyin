-- ============================================================================
-- Decommission:今天值得庆祝 / celebration_board 仪式板
--
-- 背景:
--   用户已批准删除「今天值得庆祝」整块功能。
--   生产只读核验已确认 celebration_board / celebration_board_slot /
--   celebration_board_member 三张表均为 0 行；若人工落库前复核发现非 0，必须停。
--   本文件包含 DROP TABLE / ALTER TABLE，MySQL 会隐式提交，不能声明
--   runner: requires=transaction。
--
--
-- ⚠️ 执行顺序(2026-08-29 实跑定):这是「下线」不是「加列」,③先落库那条要反过来。
--   第一段(DELETE 种子行)可以在合并前跑 —— 老 jar 顶多是该主题消失,这本来就是目的。
--   第二段(DROP TABLE ×3 + DROP COLUMN play_kind)**必须等本 PR 合并部署之后**再跑;
--   线上旧 jar 的 CmsTopicTemplateMeta / TopicTemplateDetailVO / TopicTemplateListItemVO
--   仍映射 play_kind,提前 DROP 会让模板列表页直接 Unknown column。
--
-- 已执行状态:DELETE 段已于 2026-08-29 在生产执行并回读确认(八项目标行全部归零,
--   未产生孤儿 —— 库里既存的 13 条无主 node/chapter 指向 topic_id 12-17,与本次无关)。
--   DROP 段尚未执行。
--
-- 只写文件，不在开发阶段连接或写生产库。
-- ============================================================================

-- ======================== 人工落库前只读预检 ========================
-- SELECT 'celebration_board' AS table_name, COUNT(*) AS rows FROM celebration_board
-- UNION ALL SELECT 'celebration_board_slot', COUNT(*) FROM celebration_board_slot
-- UNION ALL SELECT 'celebration_board_member', COUNT(*) FROM celebration_board_member;
-- 期望三行 rows 全为 0；任一非 0 即 NO-GO。
--
-- SELECT COUNT(*) AS topic_rows
--   FROM cms_topic
--  WHERE name = '今天值得庆祝' OR create_by = 'seed_celebrate_topic_20260821';
-- SELECT COUNT(*) AS game_rows
--   FROM cms_member_template
--  WHERE create_by = 'seed_celebrate_games_20260821';

-- ======================== 删除主题种子和玩法种子 ========================
DELETE pr
  FROM product_role pr
  JOIN cms_topic t ON t.id = pr.topic_id
 WHERE t.name = '今天值得庆祝'
    OR t.create_by = 'seed_celebrate_topic_20260821';

DELETE m
  FROM cms_topic_template_meta m
  JOIN cms_topic t ON t.id = m.topic_id
 WHERE t.name = '今天值得庆祝'
    OR t.create_by = 'seed_celebrate_topic_20260821';

-- 2026-08-29 实跑修正:原本这里有一句
--   DELETE cm FROM cms_topic_chapter_meta cm JOIN cms_topic_chapter ...
-- 生产库 **没有 cms_topic_chapter_meta 这张表**,实跑时报 1146 并中断整个脚本
-- (前两句已提交、后六句未执行,留下半执行状态)。该表不存在即无行可删,整句删除。
-- 教训:写迁移前要对每一张被引用的表做 information_schema 存在性预检,
-- 不能只核 celebration_board* 三张就宣称「生产只读核验已确认」。

DELETE n
  FROM cms_topic_node n
  JOIN cms_topic t ON t.id = n.topic_id
 WHERE t.name = '今天值得庆祝'
    OR t.create_by = 'seed_celebrate_topic_20260821'
    OR n.create_by = 'seed_celebrate_topic_20260821';

DELETE ch
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝'
    OR t.create_by = 'seed_celebrate_topic_20260821';

DELETE FROM cms_topic
 WHERE name = '今天值得庆祝'
    OR create_by = 'seed_celebrate_topic_20260821';

DELETE FROM sys_category
 WHERE create_by = 'seed_celebrate_topic_20260821'
   AND category_name = '礼品纪念';

DELETE FROM cms_template_library
 WHERE create_by = 'seed_celebrate_games_20260821';

DELETE FROM cms_member_template
 WHERE create_by = 'seed_celebrate_games_20260821';

-- ======================== 删除仪式板空表 ========================
DROP TABLE IF EXISTS celebration_board_member;
DROP TABLE IF EXISTS celebration_board_slot;
DROP TABLE IF EXISTS celebration_board;

-- ======================== 删除仪式板专属模板字段 ========================
SET @play_kind_exists := (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'cms_topic_template_meta'
       AND column_name = 'play_kind'
);

SET @drop_play_kind_sql := IF(
    @play_kind_exists = 1,
    'ALTER TABLE cms_topic_template_meta DROP COLUMN play_kind',
    'SELECT ''cms_topic_template_meta.play_kind already removed'' AS noop'
);

PREPARE drop_play_kind_stmt FROM @drop_play_kind_sql;
EXECUTE drop_play_kind_stmt;
DEALLOCATE PREPARE drop_play_kind_stmt;

-- ======================== 落库后回读 ========================
-- SELECT table_name
--   FROM information_schema.tables
--  WHERE table_schema = DATABASE()
--    AND table_name IN ('celebration_board','celebration_board_slot','celebration_board_member');
-- 期望 0 行。
--
-- SELECT column_name
--   FROM information_schema.columns
--  WHERE table_schema = DATABASE()
--    AND table_name = 'cms_topic_template_meta'
--    AND column_name = 'play_kind';
-- 期望 0 行。
--
-- SELECT COUNT(*) AS celebration_topic_rows
--   FROM cms_topic
--  WHERE name = '今天值得庆祝'
--     OR create_by = 'seed_celebrate_topic_20260821';
-- SELECT COUNT(*) AS celebration_game_rows
--   FROM cms_member_template
--  WHERE create_by = 'seed_celebrate_games_20260821';
-- 期望均为 0。
