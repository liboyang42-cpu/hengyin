-- 只读预检：migration_20261028_remove_wildlife_theme.sql 落库前先跑。不修改任何数据。
-- 第 1 段必须恰好 1 行(990052–990056 里名为「野生邻居观察局」的那条);不是 1 行就停。
-- 第 2 段每个计数都必须是 0:任何一项 > 0 说明已经有人报名 / 购买 / 开玩 / 结算过这个主题，
-- 软删会让这些玩家的入口失效 —— 停下交用户另定处置(退款 / 保留只读),不得直接跑迁移。
-- 2026-10-28 修正：生产库没有 explore_bundle_order / explore_bundle_grant(随 migration_doc2_f3_bundle_ledger_20261022
-- 落库，生产尚未执行)，原第 2 段会直接报 1146;改成只查生产已有的表，并去掉 SET 用户变量(只读预检通道不放行)。
-- 若 F3 迁移已在目标库执行，另补 explore_bundle_order / explore_bundle_grant 两项计数。
-- 2026-10-28 生产实跑：第 1 段 1 行(990056,is_template=0、del_flag=0),第 2 段 9 项全为 0;随后迁移已执行。
SELECT VERSION() AS mysql_version;

SELECT id, name, is_template, status, user_status, lifecycle, self_play, is_recommend, del_flag
  FROM cms_topic
 WHERE id BETWEEN 990052 AND 990056
   AND name = '野生邻居观察局';

SELECT 'cms_activity' AS source, COUNT(*) AS n FROM cms_activity WHERE topic_id IN (SELECT id FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局')
UNION ALL SELECT 'cms_registration', COUNT(*) FROM cms_registration WHERE topic_id IN (SELECT id FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局')
UNION ALL SELECT 'registration_entitlement', COUNT(*) FROM registration_entitlement WHERE topic_id IN (SELECT id FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局')
UNION ALL SELECT 'redemption', COUNT(*) FROM redemption WHERE topic_id IN (SELECT id FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局')
UNION ALL SELECT 'player_run_session', COUNT(*) FROM player_run_session WHERE topic_id IN (SELECT id FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局')
UNION ALL SELECT 'topic_route_session', COUNT(*) FROM topic_route_session WHERE topic_id IN (SELECT id FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局')
UNION ALL SELECT 'player_node_progress', COUNT(*) FROM player_node_progress WHERE topic_id IN (SELECT id FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局')
UNION ALL SELECT 'topic_sale_snapshot', COUNT(*) FROM topic_sale_snapshot WHERE topic_id IN (SELECT id FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局')
UNION ALL SELECT 'coop_order', COUNT(*) FROM coop_order WHERE topic_id IN (SELECT id FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局');
