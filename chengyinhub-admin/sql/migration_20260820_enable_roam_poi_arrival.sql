-- 2026-08-20 开通 ROAM_POI_ARRIVAL：Gate 0（受控样本 + 负控回读）已由
-- OfficialEventV2ServiceTest 的 gate0_* 用例组坐实（正样本接受写 VALID evidence；
-- verifyArrival ROAM_POI 分支的各拒绝路径逐 riskCode 负控、且均断言不写 evidence；
-- 开关双向可证），并补齐了跨次到达测速锚点
-- （selectLastAcceptedArrivalLoc，堵住两次到达验证之间的瞬移）。
-- 开关保留为紧急停闸。⚠️ selectConfigByKey 走 redis 缓存优先：直改 DB 不刷缓存、静默不生效。
--   · 本次开闸的生效路径：先落库本文件，再由同 PR 的 jar 部署重启刷新配置缓存（loadingConfigCache）。
--   · 紧急停闸的唯一可靠路径：后台「系统管理→参数设置」把该键改回 false（管理端会同步刷缓存）；
--     若只能改库，改完必须重启应用或清 sys_config 缓存，否则闸没有真的关上。
-- 注意：official_event.event_point_arrival_enabled（活动自有点）仍保持关闭，不在本次范围。

update sys_config
set config_value = 'true',
    remark = 'Gate 0 已完成（2026-08-20，OfficialEventV2ServiceTest.gate0_* 受控样本+负控回读）；本开关保留为紧急停闸，置 false 即停发布与计奖',
    update_by = 'admin',
    update_time = now()
where config_key = 'official_event.roam_poi_arrival_enabled'
  and config_value = 'false';

-- 回读断言：执行后应返回 1 行且 config_value='true'。
-- ⚠️ 这只证明 DB 落了，不证明运行时生效；运行时以 jar 部署重启后的行为为准（见上方缓存说明）。
select config_key, config_value from sys_config where config_key = 'official_event.roam_poi_arrival_enabled';
