-- ================= migration_20261116_shop_games_judged.sql =================
SELECT 'migration_20261116_shop_games_judged.sql' AS running;
-- W7 x-shop-games (2026-11-16): GAME-8 / MERCHANT-5 and GAME-16.
--   sys_config shopgames.play.enabled  false (safe default): no shop-game play is issued and /api/shop-games/result
--                                       answers 409 SHOP_GAME_NOT_OPEN. Turn on only after a client plays the game
--                                       through a judged runtime session (see docs/roadmap/x-shop-games/RESULT.md).
--   sys_menu business:treasureHunt:approve  putting a treasure hunt live, split from editing it. Granted to no role.
-- Seeded only when missing; never overwrites an operator's value.
--
-- ROLLBACK (manual, only after the backend build that reads these rows has been rolled back):
--   DELETE FROM sys_config WHERE config_key = 'shopgames.play.enabled';
--   DELETE FROM sys_menu WHERE perms = 'business:treasureHunt:approve';
-- Idempotent: every insert is guarded by NOT EXISTS; re-running changes nothing.

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '店铺小游戏-开放', 'shopgames.play.enabled', 'false', 'Y', 'admin', NOW(),
       'false=不发局,结果接口返回未开放(安全默认);true=开放,结果只认服务端判定的玩法会话,积分需在店铺附近'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'shopgames.play.enabled');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '寻宝上线',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:treasureHunt:list') m),2,'#','',1,0,'F','0','0','business:treasureHunt:approve','#','migration',NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:treasureHunt:approve')
  AND EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:treasureHunt:list');

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM sys_config WHERE config_key = 'shopgames.play.enabled';
