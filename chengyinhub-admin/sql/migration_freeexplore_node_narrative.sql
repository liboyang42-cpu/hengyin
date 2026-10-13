-- migration_freeexplore_node_narrative.sql
-- 自由探索(mode2)节点叙事三列。选填,空回退 description。

-- 只读预检(chengyin-db-migrate 先跑这段确认列不存在):
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cms_topic_node'
--     AND COLUMN_NAME IN ('hook_text','card_hook_long','fragment_text');

ALTER TABLE cms_topic_node
  ADD COLUMN hook_text      VARCHAR(120) NULL COMMENT '自由探索:卡堆行一句钩子',
  ADD COLUMN card_hook_long VARCHAR(300) NULL COMMENT '自由探索:地点大卡NPC 2-3句钩子',
  ADD COLUMN fragment_text  VARCHAR(600) NULL COMMENT '自由探索:故事碎片正文(完成入手记)';
