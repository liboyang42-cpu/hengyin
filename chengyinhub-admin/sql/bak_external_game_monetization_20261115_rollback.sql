-- 回滚外部游戏变现、节点绑定、结果回执与流量激励日结表（按依赖逆序删除，不碰既有审核表）
DELETE FROM sys_menu WHERE component = 'business/externalgame/monetization';

DROP TABLE IF EXISTS external_game_daily_settlement;
DROP TABLE IF EXISTS external_game_play_session;
DROP TABLE IF EXISTS external_game_node_binding;
DROP TABLE IF EXISTS external_game_entitlement;
DROP TABLE IF EXISTS external_game_order;
DROP TABLE IF EXISTS external_game_monetization_config;
