-- 只读预检：返回 0 行时才执行下方 ALTER；返回 1 行表示 blocks 列已存在，应停止。
SELECT column_name, data_type, is_nullable, column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'cms_topic_chapter'
  AND column_name = 'blocks';

ALTER TABLE cms_topic_chapter
  ADD COLUMN blocks LONGTEXT NULL COMMENT '章节内容块(JSON 数组,应用层解析)';

-- 回滚（仅在确认没有需要保留的 blocks 数据后手工执行）：
-- ALTER TABLE cms_topic_chapter DROP COLUMN blocks;
