-- ================= migration_npc_roam_lines.sql =================
SELECT 'migration_npc_roam_lines.sql' AS running;
-- 漫游 Event 轨话术种子 · 文案版本 v1 · 确认人:Arlan · 确认日期:落库当日填
-- ⚠️ 落库前置:业务负责人逐条确认下方 15 条文案;audit_status=1 即视为已审(§9.3)。
-- 依赖(★顺序敏感,按这个顺序跑):
--   ① migration_npc_profile_seed_20260716.sql —— **全局 NPC 的唯一建档者**
--   ② migration_npc_governance_v1.sql         —— 加 active_slot / version / node_key,并把 ① 建的那条提成 ACTIVE
--   ③ 本文件                                   —— 只补话术,不建档
--
-- 落库前只读预检(2026-07-16 已跑):npc_profile 全表 0 行、npc_line_cache 全表 0 行。
--
-- ★ 本文件**不再自己建 NPC**(2026-07-17 改)。原先它也带一条 INSERT INTO npc_profile,
--   于是「建全局 NPC」这件事有两个来源,而两边守卫口径不同 ——
--   种子的是 NOT EXISTS(scope_type=3 AND del_flag='0')、且 INSERT 不设 active_slot;
--   本文件原来的是 NOT EXISTS(... AND active_slot=1)、且 INSERT 设 active_slot=1。
--   ⇒ 按「② → ① → ③」这个最自然的顺序(先改表再灌数据)跑,种子那行 active_slot 是 NULL,
--     本文件就认为「没有生效 NPC」而**再插第二条** —— 库里两条 scope_type=3,
--     且生效的那条用的是本文件里那版**没有隐私/医疗/政治边界的短 persona**,
--     而种子那版带完整合规边界的长 persona 挂着 NULL 永不生效。全程不报错。
--   ⇒ 收口成「种子唯一建档 + 本文件幂等提升」,谁先谁后都只有一条,persona 恒为种子那版。

-- 幂等提升:把种子建的那条补上 ACTIVE 槽。
--   · 若 ② 先于 ① 跑,② 的 UPDATE 扑空(那时还没有行),这条负责补;
--   · 若 ① 先于 ② 跑,② 的 UPDATE 已经提升过,这条 WHERE active_slot IS NULL 不成立 ⇒ 0 行,幂等。
--   · 已有生效行时整条不动(子查询 = 0 才放行),绝不把第二条提成 ACTIVE 去撞 uk_active_scope。
--   · 子查询套一层派生表 x 是为了绕开 MySQL 1093(不能在 UPDATE 的子查询里直接读目标表)。
UPDATE npc_profile SET active_slot = 1, update_time = NOW()
WHERE scope_type = 3 AND del_flag = '0' AND audit_status = 1 AND enabled = 1
  AND active_slot IS NULL
  AND (SELECT c FROM (SELECT COUNT(*) AS c FROM npc_profile
                      WHERE scope_type = 3 AND del_flag = '0' AND active_slot = 1) x) = 0
ORDER BY id LIMIT 1;

-- ===== 前置断言:看到「停!」就别往下跑 =====
-- 本文件只补话术,补给谁取决于「有没有生效的全局 NPC」。0 条时下面的 INSERT 会
-- **静默插 0 行**(JOIN 扑空,不报错)⇒ 话术看着落库了其实一条没有。必须先看这条。
SELECT CASE COUNT(*)
         WHEN 1 THEN 'OK:有且仅有一条生效全局 NPC,继续'
         WHEN 0 THEN '停!没有生效的全局 NPC —— 先跑 migration_npc_profile_seed_20260716.sql,再跑 migration_npc_governance_v1.sql,然后回来重跑本份'
         ELSE '停!生效全局 NPC 多于一条(uk_active_scope 本该拦住)—— 先人工处置多余行'
       END AS 前置检查
FROM npc_profile WHERE scope_type = 3 AND del_flag = '0' AND active_slot = 1;

-- 5 事件 × 3 变体;profile 取当前生效的全局 NPC;防重键 = uk_line_version(version=1)
INSERT INTO npc_line_cache (profile_id, event_type, node_id, variants_json, audit_status, version, create_time, update_time)
SELECT p.id, t.event_type, NULL, t.variants, 1, 1, NOW(), NOW()
FROM npc_profile p
JOIN (
  SELECT 'roam_start' AS event_type,
    '["出发!走到哪,地图就亮到哪","今天想往哪边走?跟着感觉就行","迷雾后面藏着什么,走过去才知道"]' AS variants
  UNION ALL SELECT 'roam_poi_first_light',
    '["新地点点亮!这一片你越来越熟了","又收下一个地方,不错的眼力","这里被你点亮了,地图上多了个记号"]'
  UNION ALL SELECT 'roam_milestone_25',
    '["已经擦亮 25 格啦,今天状态不错","四分之一个街区被你走亮了","25 格!脚下这片城市开始认识你了"]'
  UNION ALL SELECT 'roam_milestone_80',
    '["80 格!这一带快被你走透了","探索力惊人,80 格到手","地图亮了一大片,今天值得记一笔"]'
  UNION ALL SELECT 'roam_end',
    '["今天的路收进口袋了,下次接着亮","走过的每一格都算数,辛苦啦","收工!这片城市又多认识你一点"]'
) t
WHERE p.scope_type = 3 AND p.active_slot = 1 AND p.del_flag = '0'
  AND NOT EXISTS (
    SELECT 1 FROM npc_line_cache c
    WHERE c.profile_id = p.id AND c.event_type = t.event_type AND c.node_key = 0 AND c.version = 1
  );

-- ===== 收尾断言:必须肉眼核对,别只看「跑完没报错」=====
-- ① ACTIVE 全局 NPC 是哪条、叫什么。期望**恰好 1 行**、name 为空串(业务决定先不起名)、active_slot=1。
--    · 出现 2 行 = 建档来源又变回两个了(本文件不该再有 INSERT INTO npc_profile),回去看改动。
--    · name 非空 = 库里那条是旧行/别人建的,用户会看到那个名字配本轨台词 —— 确认你接受,否则先处置旧行。
SELECT id, CONCAT('[', name, ']') AS name_带括号看空串, audit_status, enabled, active_slot FROM npc_profile
WHERE scope_type = 3 AND del_flag = '0';

-- ② roam_* 五条齐不齐、各几个变体。期望 5 行,n 均为 3。
SELECT c.event_type, JSON_LENGTH(c.variants_json) AS n FROM npc_line_cache c
JOIN npc_profile p ON p.id = c.profile_id AND p.scope_type = 3 AND p.active_slot = 1
WHERE c.event_type LIKE 'roam_%' ORDER BY c.event_type;

-- ③ ★连锁伤害闸:explore_* 话术必须和 roam_* 挂在同一个 ACTIVE profile 上。
--    这里若返回「有 explore_* 但 profile_id 不等于 active_id」= mapper 改动上线后
--    自由探索途中台词会静默退化成硬编码 defaultCompanionLine(不崩、不报错、零告警)。
--    → 必须先把 explore_* 迁到 ACTIVE profile 名下,再合 jar。
--    本次预检 npc_line_cache 全表空(explore_* 种子从没落库、途中台词一直在吃兜底)
--    ⇒ 期望 0 行。本断言仍保留:成本为零,且是防「将来有人补了 explore_* 却忘了对齐 profile」的常驻闸。
SELECT c.profile_id, c.event_type,
       (SELECT id FROM npc_profile WHERE scope_type=3 AND active_slot=1 AND del_flag='0') AS active_id
FROM npc_line_cache c WHERE c.event_type LIKE 'explore_%';
