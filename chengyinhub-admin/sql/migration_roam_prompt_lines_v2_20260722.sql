-- ================= migration_roam_prompt_lines_v2_20260722.sql =================
SELECT 'migration_roam_prompt_lines_v2_20260722.sql' AS running;
-- 无形象漫游提示话术 v2：旧 v1 先下线，新文案以待审状态入池。
--
-- 依赖：migration_npc_governance_v1.sql 与 migration_npc_roam_lines.sql 已落库。
-- 本文件只改 npc_line_cache 数据，不建表；生产执行前仍须走只读预检和人工确认。
--
-- 为什么先全量静默：v1 中含“跟着感觉”“城市认识你”等拟人化话术。
-- 在 v2 被内容审核通过前，cacheOnly 调用返回空行并静默，不能回落到旧文案。

-- 仅下线当前生效全局 profile 的 v1 roam 话术，其他 scope 与未来版本均不碰。
UPDATE npc_line_cache c
JOIN npc_profile p ON p.id = c.profile_id
SET c.audit_status = 0, c.update_time = NOW()
WHERE p.scope_type = 3 AND p.active_slot = 1 AND p.del_flag = '0'
  AND c.event_type IN ('roam_start', 'roam_poi_first_light', 'roam_milestone_25', 'roam_milestone_80', 'roam_end')
  AND c.version = 1
  AND c.audit_status = 1;

-- 新文案必须经既有 audit_status 流程审核后才会由 NpcLineCacheMapper 下发。
-- 待审内容不占提示位：审核前的预期行为是静默，不是显示“暂无内容”。
INSERT INTO npc_line_cache (profile_id, event_type, node_id, variants_json, audit_status, version, create_time, update_time)
SELECT p.id, t.event_type, NULL, t.variants, 0, 2, NOW(), NOW()
FROM npc_profile p
JOIN (
  SELECT 'roam_start' AS event_type,
    '["从这里出发，地图会留下这段足迹","今天的漫游从这片区域开始","地图已准备好记录这段路线"]' AS variants
  UNION ALL SELECT 'roam_poi_first_light',
    '["新的区域已点亮","这里留下了一枚新的足迹","地图多了一处已探索的标记"]'
  UNION ALL SELECT 'roam_milestone_25',
    '["已探索 25 个格子","这段漫游已留下 25 个格子","地图记录了这段脚步"]'
  UNION ALL SELECT 'roam_milestone_80',
    '["已探索 80 个格子","这片区域已经留下不少足迹","地图记录了一段更长的漫游"]'
  UNION ALL SELECT 'roam_end',
    '["今天的路线已留在地图上","这段漫游已经结算","下次仍可从自己的足迹继续探索"]'
) t
WHERE p.scope_type = 3 AND p.active_slot = 1 AND p.del_flag = '0'
  AND NOT EXISTS (
    SELECT 1 FROM npc_line_cache c
    WHERE c.profile_id = p.id AND c.event_type = t.event_type AND c.node_key = 0 AND c.version = 2
  );

-- 内容审核完成后，须在 CMS 或经确认的 SQL 中将 v2 的 audit_status 改为 1；
-- 未审核、无 profile 或任何事件缺行时，漫游提示层都应继续静默。
SELECT c.event_type, c.version, c.audit_status, JSON_LENGTH(c.variants_json) AS variants_count
FROM npc_line_cache c
JOIN npc_profile p ON p.id = c.profile_id
WHERE p.scope_type = 3 AND p.active_slot = 1 AND p.del_flag = '0'
  AND c.event_type IN ('roam_start', 'roam_poi_first_light', 'roam_milestone_25', 'roam_milestone_80', 'roam_end')
ORDER BY c.event_type, c.version;
