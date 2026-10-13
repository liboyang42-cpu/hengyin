-- ================= migration_npc_governance_v1.sql =================
SELECT 'migration_npc_governance_v1.sql' AS running;
-- 补充方案 §9.3 Event 轨最小治理:
--  1) npc_profile 增 active_slot:生效行=1,非生效=NULL;UNIQUE(scope_type,scope_id,active_slot)
--     用 NULL 不参与唯一的特性,数据库层保证「同 scope 至多一条 ACTIVE」。
--     ★ active_slot 只用于全局 NPC(scope_type=3)。scope_type=1(剧本角色)/2(门店分身)
--       恒留 NULL —— 因为它们**本来就该允许一个 scope 挂多条**:
--       migration_npc_profile.sql:15 的 sort 注释原话「同一活动多个NPC的出场顺序(主角0/配角1)」,
--       且 selectEnabledByScope 返回 List、resolveForActivity 会把多条全取出来。
--       若给 1/2 也打 active_slot=1,uk_active_scope 会把「主角+配角」判成重复键 →
--       ERROR 1062 Duplicate entry '1-42-1' → 迁移中途中断。下面的 UPDATE 因此必须带 scope_type=3。
--  2) npc_line_cache 增 version + node_key(IFNULL(node_id,0) 生成列) + 唯一键,
--     文案更新走「新 version 行 + 旧行 audit_status 置 0」,不再裸 UPDATE 已审行。
--  3) V1 语义映射(过渡,Chat 轨再上完整状态机):audit_status=1 且 enabled=1 且 active_slot=1 ⇔ ACTIVE。
--
-- ⚠️ 本迁移只改「写入侧」。约束不改变 selectGlobalNpc / selectByProfileAndEvent 取哪条 ——
--    读路径由同 PR 的 mapper 改动(NpcProfileMapper.xml / NpcLineCacheMapper.xml)承担。
--    只跑本迁移而不合 jar = 约束是纯装饰,取哪条仍由不确定的 LIMIT 1 决定。
--
-- 落库前只读预检(2026-07-16 已跑,全部 0 行 → 落在「全空」分支,以下风险均不触发):
--   SELECT id,name,audit_status,enabled,del_flag FROM npc_profile WHERE scope_type=3;          -- → 0 行(全表空)
--   SELECT profile_id,event_type,IFNULL(node_id,0) nk,COUNT(*) n FROM npc_line_cache
--     GROUP BY 1,2,3 HAVING n>1;                                                                -- → 0 行(无重复)
--   SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
--     AND ((TABLE_NAME='npc_profile' AND COLUMN_NAME='active_slot')
--      OR (TABLE_NAME='npc_line_cache' AND COLUMN_NAME IN ('version','node_key')));             -- → 0 行(列不存在)
--
-- ⚠️ 若将来在别的库重跑:全局 NPC(scope_type=3)生效候选必须 ≤1 条;>1 条时下面的唯一索引
--    会失败 —— 这是设计意图不是 bug,需先人工停用多余行再重跑(这正是要建约束的原因)。
-- ⚠️ MySQL 8:ADD COLUMN 走 INSTANT 算法(秒级、不锁表、不重建);生成列 STORED 会重建但本表 0 行。
-- ⚠️ 不用 ADD COLUMN IF NOT EXISTS(MariaDB 专属,MySQL 8 报 1064);幂等靠上面的只读预检。

ALTER TABLE npc_profile ADD COLUMN active_slot TINYINT NULL COMMENT '1=生效槽(仅全局NPC scope_type=3 用,同scope唯一);NULL=非生效/非全局';
-- ★ 必须带 scope_type = 3:活动角色(1)/门店分身(2)允许同 scope 多条(主角+配角),
--   给它们打 active_slot=1 会撞 uk_active_scope(1062)。它们恒留 NULL,不参与唯一。
UPDATE npc_profile SET active_slot = 1 WHERE scope_type = 3 AND enabled = 1 AND audit_status = 1 AND del_flag = '0';
ALTER TABLE npc_profile ADD UNIQUE KEY uk_active_scope (scope_type, scope_id, active_slot);

ALTER TABLE npc_line_cache ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT '话术版本,更新=新version行';
ALTER TABLE npc_line_cache ADD COLUMN node_key BIGINT AS (IFNULL(node_id, 0)) STORED COMMENT '唯一键用(NULL节点→0)';
ALTER TABLE npc_line_cache ADD UNIQUE KEY uk_line_version (profile_id, event_type, node_key, version);

-- 回读断言:期望 0(本次预检时 npc_profile 全表为空,Task 4 的种子才会造出第一条)
SELECT COUNT(*) AS active_global FROM npc_profile WHERE scope_type = 3 AND active_slot = 1;
