-- ================= migration_20261115_game_mechanics.sql =================
SELECT 'migration_20261115_game_mechanics.sql' AS running;
-- G2 game-mechanics (2026-11-15): treasure hunts (GAM-06), asynchronous duels (GAM-06), the walk-test gate (GAM-13),
-- and the verified-player-creator default (GAM-12).
--   treasure_hunt / _item / _find / _progress  hidden items across several places, location-trusted finds, one final reward.
--   game_duel                                   friend or club-mate duel on one journey; server reads both completions. No money.
--   topic_walk_test / topic_walk_test_waiver    desk rehearsal + on-site test records before publishing; operator waiver with a note.
--   sys_config  creator.walktest.gate           OFF | ADVISE (default: shown, not blocking) | ENFORCE (blocks publishing).
--   sys_config  creator.player.verified_unlock  false (default = today's player defaults) | true (verified player creators get branching + mechanics).
--   cms_points_setting id/event_type 41         寻宝完成 reward rule (30 points; editable in 积分规则), daily cap row when governance is installed.
--   admin menus 寻宝管理 (business:treasureHunt:list|edit), 试走记录 (business:walkTest:list|waive) — granted to no role.
--
-- ROLLBACK (manual; only after the backend build that reads these tables is rolled back):
--   DELETE FROM sys_menu WHERE perms IN ('business:treasureHunt:list','business:treasureHunt:edit','business:walkTest:list','business:walkTest:waive');
--   DELETE FROM sys_config WHERE config_key IN ('creator.walktest.gate','creator.player.verified_unlock');
--   DELETE FROM cms_points_setting WHERE id = 41 AND event_type = 41;
--   DELETE FROM points_award_cap WHERE event_type = 41;   -- only if the points governance migration is applied
--   DROP TABLE IF EXISTS topic_walk_test_waiver; DROP TABLE IF EXISTS topic_walk_test; DROP TABLE IF EXISTS game_duel;
--   DROP TABLE IF EXISTS treasure_hunt_progress; DROP TABLE IF EXISTS treasure_hunt_find;
--   DROP TABLE IF EXISTS treasure_hunt_item; DROP TABLE IF EXISTS treasure_hunt;
-- Additive only. Missing tables make the new routes answer an honest "not available yet"; no existing flow reads them.
-- Privacy: no raw coordinates of players are stored here. Finds and on-site tests keep only the location-trust
-- decision and the trail row id (location_trust_fix, 30-day retention).

CREATE TABLE IF NOT EXISTS treasure_hunt (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  title              VARCHAR(64)   NOT NULL,
  intro              VARCHAR(1000) DEFAULT NULL,
  cover_img          VARCHAR(500)  DEFAULT NULL,
  city_code          VARCHAR(16)   DEFAULT NULL,
  status             TINYINT       NOT NULL DEFAULT 0 COMMENT '0 draft, 1 in review, 2 live, 3 sent back, 4 ended',
  creator_member_id  BIGINT        DEFAULT NULL COMMENT 'player creator; NULL when operations wrote it',
  ai_assisted        TINYINT       NOT NULL DEFAULT 0 COMMENT '1 when clues were drafted with AI (shown as AI-assisted)',
  review_note        VARCHAR(300)  DEFAULT NULL,
  start_time         DATETIME      DEFAULT NULL,
  end_time           DATETIME      DEFAULT NULL,
  create_by          VARCHAR(64)   DEFAULT NULL,
  create_time        DATETIME      NOT NULL,
  update_by          VARCHAR(64)   DEFAULT NULL,
  update_time        DATETIME      DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_treasure_hunt_status (status, city_code),
  KEY idx_treasure_hunt_creator (creator_member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='G2 treasure hunt';

CREATE TABLE IF NOT EXISTS treasure_hunt_item (
  id        BIGINT        NOT NULL AUTO_INCREMENT,
  hunt_id   BIGINT        NOT NULL,
  sort      INT           NOT NULL DEFAULT 0,
  name      VARCHAR(40)   NOT NULL COMMENT 'revealed after the find',
  clue      VARCHAR(300)  NOT NULL COMMENT 'shown before the find',
  lat       DECIMAL(10,6) NOT NULL COMMENT 'GCJ-02; never sent to players before the find',
  lng       DECIMAL(10,6) NOT NULL,
  radius_m  INT           NOT NULL DEFAULT 60 COMMENT 'find radius 20-300 m',
  PRIMARY KEY (id),
  KEY idx_treasure_item_hunt (hunt_id, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='G2 treasure hunt hidden items';

CREATE TABLE IF NOT EXISTS treasure_hunt_find (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  hunt_id    BIGINT      NOT NULL,
  item_id    BIGINT      NOT NULL,
  member_id  BIGINT      NOT NULL,
  found_at   DATETIME    NOT NULL,
  fix_id     BIGINT      DEFAULT NULL COMMENT 'location_trust_fix.id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_treasure_find (item_id, member_id),
  KEY idx_treasure_find_member (member_id, hunt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='G2 treasure finds (location trusted)';

CREATE TABLE IF NOT EXISTS treasure_hunt_progress (
  id             BIGINT    NOT NULL AUTO_INCREMENT,
  hunt_id        BIGINT    NOT NULL,
  member_id      BIGINT    NOT NULL,
  found_count    INT       NOT NULL DEFAULT 0,
  completed_at   DATETIME  DEFAULT NULL,
  reward_points  INT       NOT NULL DEFAULT 0,
  update_time    DATETIME  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_treasure_progress (hunt_id, member_id),
  KEY idx_treasure_progress_member (member_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='G2 treasure hunt progress per player';

CREATE TABLE IF NOT EXISTS game_duel (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  topic_id            BIGINT       NOT NULL COMMENT 'the journey both players finish',
  challenger_id       BIGINT       NOT NULL,
  opponent_id         BIGINT       NOT NULL,
  relation            VARCHAR(8)   NOT NULL COMMENT 'FRIEND or CLUB',
  status              VARCHAR(12)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, ACTIVE, DECLINED, DONE, EXPIRED',
  created_at          DATETIME(3)  NOT NULL,
  accepted_at         DATETIME(3)  DEFAULT NULL,
  expires_at          DATETIME(3)  NOT NULL,
  challenger_seconds  INT          DEFAULT NULL,
  challenger_done_at  DATETIME(3)  DEFAULT NULL,
  opponent_seconds    INT          DEFAULT NULL,
  opponent_done_at    DATETIME(3)  DEFAULT NULL,
  result              VARCHAR(12)  DEFAULT NULL COMMENT 'CHALLENGER, OPPONENT or DRAW',
  version             INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_duel_challenger (challenger_id, status),
  KEY idx_duel_opponent (opponent_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='G2 asynchronous duel (no money, no stake)';

CREATE TABLE IF NOT EXISTS topic_walk_test (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  topic_id       BIGINT        NOT NULL,
  kind           VARCHAR(8)    NOT NULL COMMENT 'DESK or ONSITE',
  member_id      BIGINT        NOT NULL,
  recorded_at    DATETIME      NOT NULL,
  nodes_checked  INT           NOT NULL DEFAULT 0,
  nodes_total    INT           NOT NULL DEFAULT 0,
  note           VARCHAR(300)  DEFAULT NULL,
  fix_decision   VARCHAR(24)   DEFAULT NULL COMMENT 'ONSITE: location trust decision',
  fix_id         BIGINT        DEFAULT NULL,
  near_node_id   BIGINT        DEFAULT NULL COMMENT 'ONSITE: the node the fix was near',
  PRIMARY KEY (id),
  KEY idx_walk_test_topic (topic_id, kind, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='G2 walk-test records before publishing';

CREATE TABLE IF NOT EXISTS topic_walk_test_waiver (
  topic_id     BIGINT        NOT NULL,
  waived_by    VARCHAR(64)   NOT NULL,
  note         VARCHAR(300)  NOT NULL,
  create_time  DATETIME      NOT NULL,
  PRIMARY KEY (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='G2 operator waiver of the walk-test gate';

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '创作-试走门槛', 'creator.walktest.gate', 'ADVISE', 'Y', 'admin', NOW(),
       'OFF=不检查;ADVISE=发布前展示但不拦;ENFORCE=缺桌面排练或实地测试时不能发布(运营可豁免)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'creator.walktest.gate');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '创作-认证玩家解锁分支与机制', 'creator.player.verified_unlock', 'false', 'Y', 'admin', NOW(),
       'true=持 verified_creator 能力的玩家创作者可用分支与游戏机制;false=沿用玩家默认(业主决定前保持 false)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'creator.player.verified_unlock');

INSERT INTO cms_points_setting (id, title, value, description, type, event_type, status, sort_order, del_flag, create_by, create_time)
SELECT 41, '寻宝完成', 30, '找齐一场寻宝的全部宝藏', 1, 41, 1, 41, 0, 'migration', NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM cms_points_setting WHERE id = 41)
  AND NOT EXISTS (SELECT 1 FROM cms_points_setting WHERE type = 1 AND event_type = 41 AND del_flag = 0);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '寻宝管理',
       COALESCE((SELECT m.parent_id FROM (SELECT parent_id FROM sys_menu WHERE perms='business:roamPoi:list' LIMIT 1) m), 0),
       62,'treasure-hunt','business/treasurehunt/index',1,0,'C','0','0','business:treasureHunt:list','guide','migration',NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:treasureHunt:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '寻宝维护',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:treasureHunt:list') m),1,'#','',1,0,'F','0','0','business:treasureHunt:edit','#','migration',NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:treasureHunt:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '试走记录',
       COALESCE((SELECT m.parent_id FROM (SELECT parent_id FROM sys_menu WHERE perms='business:roamPoi:list' LIMIT 1) m), 0),
       63,'walk-test','business/walktest/index',1,0,'C','0','0','business:walkTest:list','checkbox','migration',NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:walkTest:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '试走豁免',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:walkTest:list') m),1,'#','',1,0,'F','0','0','business:walkTest:waive','#','migration',NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:walkTest:waive');

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
          AND table_name IN ('treasure_hunt','treasure_hunt_item','treasure_hunt_find','treasure_hunt_progress',
                             'game_duel','topic_walk_test','topic_walk_test_waiver')) = 7
   AND (SELECT COUNT(*) FROM sys_config WHERE config_key IN ('creator.walktest.gate','creator.player.verified_unlock')) = 2
   AND (SELECT COUNT(*) FROM sys_menu WHERE perms IN ('business:treasureHunt:list','business:treasureHunt:edit',
                                                      'business:walkTest:list','business:walkTest:waive')) = 4 AS assertion_ok;
