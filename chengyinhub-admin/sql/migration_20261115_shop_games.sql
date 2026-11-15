-- ================= migration_20261115_shop_games.sql =================
SELECT 'migration_20261115_shop_games.sql' AS running;
-- S5 shop games (slug shop-games, 2026-11-15): each shop gets a game profile drawn from a pool that fits its kind.
-- 1. cy_shop_game_profile: per shop, the merchant's choice (games_json), an operator override (operator_games_json),
--    an operator kind override and a lock. No rows inserted: every shop starts on its kind's default game.
-- 2. cy_shop_game_curation: operators switch a game off for every shop. No rows: every game is on.
-- 3. cy_shop_game_audit: every merchant choice and operator override, before and after.
-- 4. cy_shop_game_play: one play per player, shop and day (uk), with its result and the points awarded.
-- 5. Points: rule cms_points_setting event_type 31 (店铺小游戏, 10 points per win) and a points governance daily cap
--    of 30 per member (programme PLAY). Wins go through PointsAwardServiceImpl.award, which applies the cap.
-- 6. Console menu 店铺小游戏 under 运营规则 with two permissions, granted to no role.
-- No money: nothing here is paid, and plans only change how many games a shop may offer.
-- Idempotent: CREATE TABLE IF NOT EXISTS; seed and menu rows guarded by existence checks.
--
-- ROLLBACK (manual, after the backend build that reads these tables has been rolled back):
--   DELETE FROM sys_menu WHERE perms = 'business:shopgames:edit';
--   DELETE FROM sys_menu WHERE perms = 'business:shopgames:list';
--   DELETE FROM points_award_cap WHERE event_type = 31;
--   UPDATE cms_points_setting SET status = 0 WHERE type = 1 AND event_type = 31;   -- keep the row: ledger rows cite it
--   DROP TABLE IF EXISTS cy_shop_game_play;      -- play history is lost; export first if needed
--   DROP TABLE IF EXISTS cy_shop_game_audit;
--   DROP TABLE IF EXISTS cy_shop_game_curation;
--   DROP TABLE IF EXISTS cy_shop_game_profile;
-- The backend reads missing tables as "default profile for every shop" and a launch then answers available=false,
-- so applying this after the deploy is safe.

CREATE TABLE IF NOT EXISTS cy_shop_game_profile (
  merchant_id          BIGINT        NOT NULL COMMENT 'mms_merchant.id',
  kind_override        VARCHAR(32)   NULL COMMENT 'operator-set shop kind (CAFE, BOOKSHOP, ...); NULL = from categories',
  games_json           VARCHAR(1000) NULL COMMENT 'merchant choice: [{"key":"qaPick","templateId":12}]',
  operator_games_json  VARCHAR(1000) NULL COMMENT 'operator override; wins over the merchant choice when set',
  locked               TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 = merchant cannot change the choice',
  update_by            VARCHAR(64)   NOT NULL DEFAULT '',
  update_time          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (merchant_id),
  KEY idx_shop_game_profile_time (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S5 shop game profile per shop';

CREATE TABLE IF NOT EXISTS cy_shop_game_curation (
  game_key     VARCHAR(32)   NOT NULL COMMENT 'ShopGameCatalog game key',
  enabled      TINYINT(1)    NOT NULL DEFAULT 1,
  reason       VARCHAR(255)  NULL,
  update_by    VARCHAR(64)   NOT NULL DEFAULT '',
  update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (game_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S5 games operators switched on or off for every shop';

CREATE TABLE IF NOT EXISTS cy_shop_game_audit (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  merchant_id  BIGINT        NOT NULL,
  actor        VARCHAR(64)   NOT NULL,
  action       VARCHAR(32)   NOT NULL COMMENT 'MERCHANT_CHOICE | OPERATOR_OVERRIDE',
  before_json  VARCHAR(4000) NULL,
  after_json   VARCHAR(4000) NULL,
  reason       VARCHAR(255)  NULL,
  create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_shop_game_audit_shop (merchant_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S5 shop game profile changes';

CREATE TABLE IF NOT EXISTS cy_shop_game_play (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  member_id    BIGINT        NOT NULL,
  merchant_id  BIGINT        NOT NULL,
  poi_id       BIGINT        NULL COMMENT 'roam_poi.id when launched at a map place',
  game_key     VARCHAR(32)   NOT NULL,
  template_id  BIGINT        NULL COMMENT 'cms_member_template.id that plays the game',
  source       VARCHAR(24)   NOT NULL COMMENT 'ROAM_CHECKIN | JOURNEY_NODE | CITY_LANDING',
  status       VARCHAR(12)   NOT NULL COMMENT 'ISSUED | WON | LOST | EXPIRED',
  rounds_won   INT           NULL,
  points       INT           NOT NULL DEFAULT 0,
  play_day     CHAR(10)      NOT NULL COMMENT 'yyyy-MM-dd, Asia/Shanghai',
  issued_at    DATETIME      NOT NULL,
  expires_at   DATETIME      NOT NULL,
  finished_at  DATETIME      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_shop_game_play_day (member_id, merchant_id, play_day),
  KEY idx_shop_game_play_shop (merchant_id, play_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='S5 shop game plays: one per player, shop and day';

-- Points rule (cms_points_setting.id has no AUTO_INCREMENT; 31 matches the event type). Value editable in 积分规则.
INSERT INTO `cms_points_setting` (`id`, `title`, `value`, `description`, `type`, `event_type`, `status`, `sort_order`, `del_flag`, `create_by`, `create_time`)
SELECT * FROM (SELECT 31 AS k, '店铺小游戏' AS a, 10 AS b, '店铺小游戏获胜奖励(每家店每天一局)' AS c, 1 AS d, 31 AS e, 1 AS f, 31 AS g, 0 AS h, 'migration' AS i, NOW() AS j) t
WHERE NOT EXISTS (SELECT 1 FROM `cms_points_setting` cps WHERE cps.`type` = 1 AND cps.`event_type` = 31 AND cps.`del_flag` = 0)
  AND NOT EXISTS (SELECT 1 FROM `cms_points_setting` cps WHERE cps.`id` = 31);

INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 31, '店铺小游戏', 'PLAY', 30, '0', 'migration', NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 31);

-- Admin menu under 运营规则 (path 'rules'). Not granted to any role here.
SET @shopGamesRulesCount := (SELECT COUNT(1) FROM sys_menu WHERE menu_type = 'M' AND path = 'rules');
SET @shopGamesRules := (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'M' AND path = 'rules');
SET @shopGamesExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:shopgames:list');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '店铺小游戏', @shopGamesRules, 22, 'shop-games', 'business/shopgames/index', '', 'ShopGames',
       1, 0, 'C', '0', '0', 'business:shopgames:list', 'example', 'migration', NOW(),
       'S5：各类店铺的小游戏池、单店设置与上下架'
FROM dual WHERE @shopGamesRulesCount = 1 AND @shopGamesExisting = 0;
SET @shopGamesMenu := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:shopgames:list' AND menu_type = 'C');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '店铺小游戏修改', @shopGamesMenu, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:shopgames:edit', '#',
       'migration', NOW(), 'S5：改单店小游戏、店铺类型、锁定，以及小游戏上下架(须填写原因，留审计)'
FROM dual WHERE @shopGamesMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:shopgames:edit');

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE()
      AND table_name IN ('cy_shop_game_profile', 'cy_shop_game_curation', 'cy_shop_game_audit', 'cy_shop_game_play')) = 4
  AND (SELECT COUNT(1) FROM cms_points_setting WHERE type = 1 AND event_type = 31 AND del_flag = 0) >= 1
  AND (SELECT COUNT(1) FROM points_award_cap WHERE event_type = 31) = 1
  AND (SELECT COUNT(1) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
        WHERE m.perms LIKE 'business:shopgames:%') = 0
) AS shop_games_ready;
