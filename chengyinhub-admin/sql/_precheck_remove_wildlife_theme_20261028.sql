-- 只读预检：migration_20261028_remove_wildlife_theme.sql 落库前先跑。不修改任何数据。
-- 第 1 段必须恰好 1 行(990052–990056 里名为「野生邻居观察局」的那条);不是 1 行就停。
-- 第 2 段每个计数都必须是 0:任何一项 > 0 说明已经有人报名 / 购买 / 开玩 / 结算过这个主题，
-- 软删会让这些玩家的入口失效 —— 停下交用户另定处置(退款 / 保留只读),不得直接跑迁移。
SELECT VERSION() AS mysql_version;

SELECT id, name, is_template, status, user_status, lifecycle, self_play, is_recommend, del_flag
  FROM cms_topic
 WHERE id BETWEEN 990052 AND 990056
   AND name = '野生邻居观察局';

SET @wildlife_topic_id = (SELECT id FROM cms_topic
                           WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局' AND del_flag = 0);

SELECT 'cms_activity' AS source, COUNT(*) AS n FROM cms_activity WHERE topic_id = @wildlife_topic_id
UNION ALL SELECT 'cms_registration', COUNT(*) FROM cms_registration WHERE topic_id = @wildlife_topic_id
UNION ALL SELECT 'registration_entitlement', COUNT(*) FROM registration_entitlement WHERE topic_id = @wildlife_topic_id
UNION ALL SELECT 'redemption', COUNT(*) FROM redemption WHERE topic_id = @wildlife_topic_id
UNION ALL SELECT 'player_run_session', COUNT(*) FROM player_run_session WHERE topic_id = @wildlife_topic_id
UNION ALL SELECT 'topic_route_session', COUNT(*) FROM topic_route_session WHERE topic_id = @wildlife_topic_id
UNION ALL SELECT 'player_node_progress', COUNT(*) FROM player_node_progress WHERE topic_id = @wildlife_topic_id
UNION ALL SELECT 'topic_sale_snapshot', COUNT(*) FROM topic_sale_snapshot WHERE topic_id = @wildlife_topic_id
UNION ALL SELECT 'explore_bundle_order', COUNT(*) FROM explore_bundle_order WHERE topic_id = @wildlife_topic_id
UNION ALL SELECT 'explore_bundle_grant', COUNT(*) FROM explore_bundle_grant WHERE topic_id = @wildlife_topic_id
UNION ALL SELECT 'coop_order', COUNT(*) FROM coop_order WHERE topic_id = @wildlife_topic_id;
