-- 官方模板「预制人生 · 四站试玩版」(990059)补注册状态键(2026-11-01 上线前生产走查)
-- 现象:模板「应用」后发布被拒「主题发布失败:以下状态键未注册 → [counter.instability(节点:第三站 · 会议出口)]」。
-- 原因:migration_seed_prefab_life_20260918 把玩法里的 counter.energy / counter.instability 写进了站点配置,
--       但没在 theme_state_key 注册;种子直写库,从没过发布闸。每个套用这份模板的人都发布不了。
-- 配套代码:模板/主题完整复制时连同已注册状态键一起复制(TopicContentCopyServiceImpl)。
-- 两个计数器是玩法内部计分,不上角色卡(visible=0),初值 0,每局重置(persist_scope NULL=run)。
-- 执行方式:走 chengyin-db-migrate,只读预检后确认。可重复执行:已注册的键不重复写。

INSERT INTO theme_state_key (topic_id, state_key, key_type, scope, label, initial_value, visible, create_time, update_time)
SELECT t.id, k.state_key, 'counter', 'run', k.label, 0, 0, NOW(3), NOW(3)
FROM cms_topic t
JOIN (SELECT 'counter.energy' AS state_key, '能量' AS label
      UNION ALL SELECT 'counter.instability', '偏离') k
WHERE t.id = 990059 AND t.create_by = 'seed_prefab_life_20260918'
  AND NOT EXISTS (SELECT 1 FROM theme_state_key s WHERE s.topic_id = t.id AND s.state_key = k.state_key);

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM cms_topic WHERE id = 990059 AND create_by = 'seed_prefab_life_20260918') = 0
  OR (SELECT COUNT(*) FROM theme_state_key WHERE topic_id = 990059
        AND state_key IN ('counter.energy', 'counter.instability') AND key_type = 'counter') = 2
) AS prefab_life_state_keys_ready;

-- 回退:DELETE FROM theme_state_key WHERE topic_id = 990059 AND state_key IN ('counter.energy','counter.instability');
