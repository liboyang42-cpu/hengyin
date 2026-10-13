-- ============================================================================
-- 主题节点 NPC:把 npc_profile.scope_type 的作用域注释补上 4（挂 cms_topic_node.id）
--
-- 背景:CR-927「商家上传节点 NPC」(产品拍板 2026-09-15 #28 → 做)。
--   **不新开表**:复用 npc_profile 的 name/avatar/greeting/voice_*/audit_status/enabled,
--   新作用域 scope_type=4 + scope_id=cms_topic_node.id,一节点一条 ——
--   写入恒带 active_slot=1,吃 migration_npc_governance_v1.sql 的
--   uk_active_scope(scope_type,scope_id,active_slot) 唯一键(并发双击也插不出第二条)。
--
-- 本文件【只改列注释】:不加列、不搬数据、不改结构,是说明性 DDL ——
--   让 schema 自己能回答「scope_type=4 是什么」。行为改动全在代码侧。
--
-- 类别:说明性 DDL(COMMENT) / 顺序:npc_profile 建表之后(npc_governance_v1 亦已落) /
-- 幂等:是(按 information_schema 现值判,重复执行只打跳过) / 可逆:改回原注释即可
-- 回读断言:见文件末尾(期望 1)。
-- ============================================================================

SET @db := DATABASE();

SET @sql := (
  SELECT IF(
    (SELECT COLUMN_COMMENT FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'npc_profile' AND COLUMN_NAME = 'scope_type') LIKE '%4=%',
    'SELECT ''npc_profile.scope_type 注释已含 4,跳过'' AS skipped',
    'ALTER TABLE npc_profile MODIFY COLUMN scope_type TINYINT NOT NULL COMMENT ''1=剧本角色(挂activity) 2=门店分身(挂merchant) 3=全局城瘾NPC 4=主题节点NPC(挂cms_topic_node.id)''')
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回读断言:期望 1。为 0 表示注释没写上,scope 4 在 schema 里仍是「不存在的作用域」。
SELECT COUNT(*) AS scope_type_comment_documents_node_npc
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'npc_profile' AND COLUMN_NAME = 'scope_type'
  AND COLUMN_COMMENT LIKE '%4=%';
