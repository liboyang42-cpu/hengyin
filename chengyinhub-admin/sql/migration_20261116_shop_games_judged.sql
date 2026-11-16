-- ================= migration_20261116_shop_games_judged.sql =================
SELECT 'migration_20261116_shop_games_judged.sql' AS running;
-- W7 x-shop-games (2026-11-16): GAME-8 / MERCHANT-5 and GAME-16.
--   sys_config shopgames.play.enabled  false (safe default): no shop-game play is issued and /api/shop-games/result
--                                       answers 409 SHOP_GAME_NOT_OPEN. Turn on only after a client plays the game
--                                       through a judged runtime session (see docs/roadmap/x-shop-games/RESULT.md).
--   sys_menu business:treasureHunt:approve  putting a treasure hunt live, split from editing it. Granted to no role.
--   MERCHANT-6 sys_config merchant.plans.ready.shopGames / merchant.plans.ready.dealsSubmission  false: a paid plan
--                                       that lists a benefit whose path is not ready cannot be checked out or granted.
--   MERCHANT-6 cy_merchant_plan PRO     extraNodeGames 5 -> 2, only while it still holds the seed value 5: every pool
--                                       has 3 games, so 1 + 2 is all a shop can offer (the old 5 was never delivered).
-- Seeded only when missing; never overwrites an operator's value.
--
-- ROLLBACK (manual, only after the backend build that reads these rows has been rolled back):
--   DELETE FROM sys_config WHERE config_key = 'shopgames.play.enabled';
--   DELETE FROM sys_menu WHERE perms = 'business:treasureHunt:approve';
--   DELETE FROM sys_config WHERE config_key IN ('merchant.plans.ready.shopGames', 'merchant.plans.ready.dealsSubmission');
--   UPDATE cy_merchant_plan SET features_json = JSON_SET(features_json, '$.extraNodeGames', 5) WHERE code = 'PRO'
--     AND update_by = 'migration_20261116';
-- Idempotent: every insert is guarded by NOT EXISTS; re-running changes nothing.

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '店铺小游戏-开放', 'shopgames.play.enabled', 'false', 'Y', 'admin', NOW(),
       'false=不发局,结果接口返回未开放(安全默认);true=开放,结果只认服务端判定的玩法会话,积分需在店铺附近'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'shopgames.play.enabled');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '寻宝上线',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:treasureHunt:list') m),2,'#','',1,0,'F','0','0','business:treasureHunt:approve','#','migration',NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:treasureHunt:approve')
  AND EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:treasureHunt:list');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT * FROM (
  SELECT '商户套餐-店铺小游戏已可玩' AS n, 'merchant.plans.ready.shopGames' AS k, 'false' AS v, 'Y' AS t, 'admin' AS b, NOW() AS c,
         'false=含额外小游戏的付费套餐不能购买或开通(安全默认);玩家端真的能玩店铺小游戏后再改 true' AS r
  UNION ALL SELECT '商户套餐-优惠自助提交已上线', 'merchant.plans.ready.dealsSubmission', 'false', 'Y', 'admin', NOW(),
         'false=含每月优惠名额的付费套餐不能购买或开通(安全默认);商户端能提交优惠后再改 true'
) seed
WHERE NOT EXISTS (SELECT 1 FROM sys_config s WHERE s.config_key = seed.k);

UPDATE cy_merchant_plan
   SET features_json = JSON_SET(features_json, '$.extraNodeGames', 2), update_by = 'migration_20261116', update_time = NOW()
 WHERE code = 'PRO' AND JSON_EXTRACT(features_json, '$.extraNodeGames') = 5;

-- runner: assert=scalar-true
SELECT COUNT(*) = 3 FROM sys_config WHERE config_key IN ('shopgames.play.enabled', 'merchant.plans.ready.shopGames',
  'merchant.plans.ready.dealsSubmission');
