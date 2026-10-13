-- ============================================================================
-- 主题模板玩法承载标记 play_kind
--
-- 动机:《今天值得庆祝》的玩家玩法由仪式板(celebration_board)承载,不走常规节点流。
-- 货架卡需要一个真字段判断「开始庆祝」入口,不许按主题名硬编码(闸放对层)。
--
-- ① DDL:MySQL 8 ADD COLUMN = INSTANT,秒级不锁表。
--   预检(执行前人工跑,列已存在则跳过本文件第一句):
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_topic_template_meta'
--      AND COLUMN_NAME='play_kind';
-- ② 回填幂等:仅 play_kind IS NULL 时写。
-- ============================================================================

ALTER TABLE cms_topic_template_meta
  ADD COLUMN play_kind VARCHAR(32) NULL
  COMMENT '玩法承载:NULL=常规节点流;BOARD=仪式板(celebration_board)' AFTER main_skeleton_template_id;

UPDATE cms_topic_template_meta m
  JOIN cms_topic t ON t.id = m.topic_id
   SET m.play_kind = 'BOARD'
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0
   AND m.play_kind IS NULL;

-- 回读验证:
--   SELECT t.name, m.play_kind FROM cms_topic_template_meta m
--     JOIN cms_topic t ON t.id=m.topic_id WHERE m.play_kind IS NOT NULL;  -- 期望仅 今天值得庆祝/BOARD
-- 回滚:
--   UPDATE cms_topic_template_meta SET play_kind=NULL WHERE play_kind='BOARD';
--   ALTER TABLE cms_topic_template_meta DROP COLUMN play_kind;
