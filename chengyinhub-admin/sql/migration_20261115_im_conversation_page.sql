-- R9 BE-17 会话列表分页与索引(2026-11-15)
-- 目的:/api/im/conversations 不再对读者全部会话逐行跑「最新消息」相关子查询。
--   ① im_conversation_member(member_id, deleted, conversation_id):派生分页表只扫本人行并直接拿到会话 id。
--   ② im_message 函数索引 (conversation_id, coalesce(visible_at, create_time), id):每个会话的「最新可见消息」
--      子查询按此序取 1 行。需要 MySQL 8.0.13+(生产/TEST 为 8.0.45)。
--   ③ im_conversation(last_msg_at, id):键集分页的排序与游标比较。
-- 回退:
--   ALTER TABLE im_conversation_member DROP INDEX idx_member_deleted_conv;
--   ALTER TABLE im_message DROP INDEX idx_conv_visible_order;
--   ALTER TABLE im_conversation DROP INDEX idx_last_msg_at_id;
-- 幂等:每个索引先查 information_schema.STATISTICS,存在则跳过。大表建索引用 INPLACE/LOCK=NONE,先在 TEST 量耗时。

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_conversation_member' AND INDEX_NAME = 'idx_member_deleted_conv');
SET @ddl := IF(@idx = 0,
  'ALTER TABLE im_conversation_member ADD INDEX idx_member_deleted_conv (member_id, deleted, conversation_id), ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT ''idx_member_deleted_conv exists'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_message' AND INDEX_NAME = 'idx_conv_visible_order');
SET @ddl := IF(@idx = 0,
  'ALTER TABLE im_message ADD INDEX idx_conv_visible_order (conversation_id, (coalesce(visible_at, create_time)), id)',
  'SELECT ''idx_conv_visible_order exists'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_conversation' AND INDEX_NAME = 'idx_last_msg_at_id');
SET @ddl := IF(@idx = 0,
  'ALTER TABLE im_conversation ADD INDEX idx_last_msg_at_id (last_msg_at, id), ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT ''idx_last_msg_at_id exists'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND INDEX_NAME IN ('idx_member_deleted_conv', 'idx_conv_visible_order', 'idx_last_msg_at_id')) = 3 AS im_conversation_page_indexes;

-- 上线后在 TEST 核对执行计划(只读):
-- EXPLAIN 中派生表应走 idx_member_deleted_conv,最新消息子查询应走 idx_conv_visible_order(无 filesort)。
