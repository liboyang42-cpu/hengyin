-- ============================================================================
-- 下架生产库里的 5 个测试 / 演示主题（业主 2026-10-29 发布收口 release-closeout-20261029 要求）
--
-- 每一行同时钉住 id + 名称 + member_id，三者全部吻合才处理；不吻合的同 id 行一律不动：
--   (990062, '预制人生 · 第一站',          100092)
--   (8,      '西安大唐不夜城灯光秀',        100000)
--   (7,      '上海沉浸式戏剧',              100000)
--   (6,      '成都宽窄巷子辣味食材寻味',    100000)
--   (1,      '上海滨江 10KM 骑行',          100086)
-- 明确不碰：990052–990056 五个官方单人剧情、990068、990069。
--
-- 处置：软删 del_flag=1（读端一律按 del_flag = 0 过滤，改回 0 即恢复），写 update_by 标记与 update_time。
-- 不改 user_status / is_recommend / 章节 / 节点 / 订单 / 报名，不删行。
-- 守卫：主题若还有以下任意一项，整行跳过，留给业主另定处置（退款 / 保留只读）：
--   · cms_registration 未取消（registration_status <> 3）或支付中/已支付（payment_status IN (1,2)），
--     关联方式与仓库一致：topic_id = 主题，或 owner_type=1 AND owner_id=主题，
--     或 owner_type=2 AND owner_id 是该主题下的 cms_activity.id；
--   · player_run_session 未结束（run_state <> 'ENDED'）；
--   · topic_route_session 进行中（status = 0）。
-- 幂等：WHERE 带 del_flag = 0，重跑命中 0 行。纯 DML，不改表结构，不加锁表。
--
-- 只读预检（chengyin-db-migrate 先跑、留回执；只有 SELECT）：
--   SELECT t.id, t.name, t.member_id, t.del_flag, t.user_status, t.update_by,
--          (SELECT COUNT(*) FROM cms_registration r
--            WHERE (r.topic_id = t.id OR (r.owner_type = 1 AND r.owner_id = t.id)
--                   OR (r.owner_type = 2 AND r.owner_id IN (SELECT a.id FROM cms_activity a WHERE a.topic_id = t.id)))
--              AND (r.registration_status <> 3 OR r.payment_status IN (1, 2))) AS live_registrations,
--          (SELECT COUNT(*) FROM player_run_session s WHERE s.topic_id = t.id AND s.run_state <> 'ENDED') AS live_runs,
--          (SELECT COUNT(*) FROM topic_route_session s WHERE s.topic_id = t.id AND s.status = 0) AS live_routes
--     FROM cms_topic t
--    WHERE (t.id, t.name, t.member_id) IN ((990062, '预制人生 · 第一站', 100092), (8, '西安大唐不夜城灯光秀', 100000),
--          (7, '上海沉浸式戏剧', 100000), (6, '成都宽窄巷子辣味食材寻味', 100000), (1, '上海滨江 10KM 骑行', 100086));
--   期望：最多 5 行；live_* 全为 0 的 del_flag=0 行会被本迁移软删，live_* 非 0 的行被跳过。
--
-- 回滚（只恢复本迁移改过的行）：
--   UPDATE cms_topic SET del_flag = 0, update_by = 'rollback_unlist_test_topics_20261029', update_time = NOW()
--    WHERE (id, name, member_id) IN ((990062, '预制人生 · 第一站', 100092), (8, '西安大唐不夜城灯光秀', 100000),
--          (7, '上海沉浸式戏剧', 100000), (6, '成都宽窄巷子辣味食材寻味', 100000), (1, '上海滨江 10KM 骑行', 100086))
--      AND del_flag = 1 AND update_by = 'unlist_test_topics_20261029';
-- 按 chengyin-db-migrate 流程只读预检 + 业主确认后由人工执行（OWNER_GATED_DATA）。
-- ============================================================================

UPDATE cms_topic t
   SET t.del_flag = 1,
       t.update_by = 'unlist_test_topics_20261029',
       t.update_time = NOW()
 WHERE (t.id, t.name, t.member_id) IN ((990062, '预制人生 · 第一站', 100092), (8, '西安大唐不夜城灯光秀', 100000),
       (7, '上海沉浸式戏剧', 100000), (6, '成都宽窄巷子辣味食材寻味', 100000), (1, '上海滨江 10KM 骑行', 100086))
   AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_registration r
                    WHERE (r.topic_id = t.id OR (r.owner_type = 1 AND r.owner_id = t.id)
                           OR (r.owner_type = 2 AND r.owner_id IN (SELECT a.id FROM cms_activity a WHERE a.topic_id = t.id)))
                      AND (r.registration_status <> 3 OR r.payment_status IN (1, 2)))
   AND NOT EXISTS (SELECT 1 FROM player_run_session s WHERE s.topic_id = t.id AND s.run_state <> 'ENDED')
   AND NOT EXISTS (SELECT 1 FROM topic_route_session s WHERE s.topic_id = t.id AND s.status = 0);

-- 回读：钉住的行里，仍为 del_flag=0 的只能是被守卫跳过的那些。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
  FROM cms_topic t
 WHERE (t.id, t.name, t.member_id) IN ((990062, '预制人生 · 第一站', 100092), (8, '西安大唐不夜城灯光秀', 100000),
       (7, '上海沉浸式戏剧', 100000), (6, '成都宽窄巷子辣味食材寻味', 100000), (1, '上海滨江 10KM 骑行', 100086))
   AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_registration r
                    WHERE (r.topic_id = t.id OR (r.owner_type = 1 AND r.owner_id = t.id)
                           OR (r.owner_type = 2 AND r.owner_id IN (SELECT a.id FROM cms_activity a WHERE a.topic_id = t.id)))
                      AND (r.registration_status <> 3 OR r.payment_status IN (1, 2)))
   AND NOT EXISTS (SELECT 1 FROM player_run_session s WHERE s.topic_id = t.id AND s.run_state <> 'ENDED')
   AND NOT EXISTS (SELECT 1 FROM topic_route_session s WHERE s.topic_id = t.id AND s.status = 0);
