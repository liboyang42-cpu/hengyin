-- City Monopoly numbers + Attributes v2 (docs/delivery/backend-app-r2/CITY-MONOPOLY-SPEC.md, owner rulings 2026-11-14/15).
-- ONE currency: 积分 (ums_member.point + ums_member_points_detail via the existing CityGameMapper points statements).
-- No CITY_COIN, no wallet table; city_points_flow is a per-movement audit trail of 积分 already written to the points
-- detail table, never a balance. No financial ledger tables are altered.
-- Additive & reversible only: guarded ADD COLUMN on city_game_board / city_game_point / roam_territory / cms_topic,
-- CREATE TABLE IF NOT EXISTS for the new tables, idempotent sys_config seeds (admin-tunable approved defaults).
-- Rollback instructions (run in this order; data in the new tables is discarded):
--   DELETE FROM sys_config WHERE config_key LIKE 'city.monopoly.%' OR config_key LIKE 'attributes.v2.%';
--   DROP TABLE IF EXISTS member_archetype, member_title, member_attribute_ledger, member_attribute;
--   DROP TABLE IF EXISTS city_device_account, city_abuse_signal, city_season_score, city_season, city_challenge,
--     city_claim_evidence, city_yield_event, city_member_season, city_points_flow, city_monopoly_receipt,
--     city_monopoly_quote, city_district;
--   ALTER TABLE cms_topic DROP COLUMN persona_imprint;
--   ALTER TABLE roam_territory DROP INDEX idx_roam_territory_district, DROP COLUMN rules_kind, DROP COLUMN tier,
--     DROP COLUMN upgrade_level, DROP COLUMN invested, DROP COLUMN district_id, DROP COLUMN last_owner_visit_at,
--     DROP COLUMN decay_state, DROP COLUMN decay_levels_applied, DROP COLUMN claim_evidence_id, DROP COLUMN daily_visits,
--     DROP COLUMN daily_visits_day, DROP COLUMN challenges_today, DROP COLUMN challenges_day, DROP COLUMN upgrades_day,
--     DROP COLUMN shield_until, DROP COLUMN last_shield_at, DROP COLUMN released_at, DROP COLUMN release_reason;
--   ALTER TABLE city_game_point DROP COLUMN geohash7, DROP COLUMN district_id, DROP COLUMN tier;
--   ALTER TABLE city_game_board DROP COLUMN currency, DROP COLUMN mode, DROP COLUMN config_json,
--     DROP COLUMN season_starts_at, DROP COLUMN season_ends_at, DROP COLUMN launch_multiplier;

SET @cm_board_cols := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='city_game_board' AND column_name='launch_multiplier');
SET @cm_board_sql := IF(@cm_board_cols=0,
  'ALTER TABLE city_game_board ADD COLUMN currency varchar(16) NOT NULL DEFAULT ''POINTS'' COMMENT ''POINTS only (owner ruling 2026-11-15)'', ADD COLUMN mode varchar(16) NOT NULL DEFAULT ''DICE_EVENT'' COMMENT ''LIVE/DICE_EVENT'', ADD COLUMN config_json json DEFAULT NULL COMMENT ''per-board overrides of city.monopoly.* keys'', ADD COLUMN season_starts_at datetime(3) DEFAULT NULL, ADD COLUMN season_ends_at datetime(3) DEFAULT NULL, ADD COLUMN launch_multiplier decimal(4,2) NOT NULL DEFAULT 1.00',
  'SELECT 1');
PREPARE cm_board_stmt FROM @cm_board_sql;
EXECUTE cm_board_stmt;
DEALLOCATE PREPARE cm_board_stmt;

SET @cm_point_cols := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='city_game_point' AND column_name='tier');
SET @cm_point_sql := IF(@cm_point_cols=0,
  'ALTER TABLE city_game_point ADD COLUMN geohash7 char(7) DEFAULT NULL, ADD COLUMN district_id varchar(64) DEFAULT NULL, ADD COLUMN tier tinyint DEFAULT NULL COMMENT ''1-4 (T1 街角 .. T4 地标)''',
  'SELECT 1');
PREPARE cm_point_stmt FROM @cm_point_sql;
EXECUTE cm_point_stmt;
DEALLOCATE PREPARE cm_point_stmt;

SET @cm_terr_cols := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='roam_territory' AND column_name='rules_kind');
SET @cm_terr_sql := IF(@cm_terr_cols=0,
  'ALTER TABLE roam_territory ADD COLUMN rules_kind varchar(16) NOT NULL DEFAULT ''LEGACY'' COMMENT ''LEGACY/MONOPOLY'', ADD COLUMN tier tinyint NOT NULL DEFAULT 1, ADD COLUMN upgrade_level tinyint NOT NULL DEFAULT 0, ADD COLUMN invested bigint NOT NULL DEFAULT 0, ADD COLUMN district_id varchar(64) DEFAULT NULL, ADD COLUMN last_owner_visit_at datetime(3) DEFAULT NULL, ADD COLUMN decay_state varchar(12) NOT NULL DEFAULT ''OK'' COMMENT ''OK/WEATHERED/DECAYING'', ADD COLUMN decay_levels_applied int NOT NULL DEFAULT 0, ADD COLUMN claim_evidence_id bigint DEFAULT NULL, ADD COLUMN daily_visits int NOT NULL DEFAULT 0, ADD COLUMN daily_visits_day date DEFAULT NULL, ADD COLUMN challenges_today int NOT NULL DEFAULT 0, ADD COLUMN challenges_day date DEFAULT NULL, ADD COLUMN upgrades_day date DEFAULT NULL, ADD COLUMN shield_until datetime(3) DEFAULT NULL, ADD COLUMN last_shield_at datetime(3) DEFAULT NULL, ADD COLUMN released_at datetime(3) DEFAULT NULL, ADD COLUMN release_reason varchar(24) DEFAULT NULL, ADD KEY idx_roam_territory_district (board_id, district_id, status)',
  'SELECT 1');
PREPARE cm_terr_stmt FROM @cm_terr_sql;
EXECUTE cm_terr_stmt;
DEALLOCATE PREPARE cm_terr_stmt;

SET @cm_topic_cols := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='persona_imprint');
SET @cm_topic_sql := IF(@cm_topic_cols=0,
  'ALTER TABLE cms_topic ADD COLUMN persona_imprint tinyint NOT NULL DEFAULT 0 COMMENT ''Attributes v2 imprint opt-in, OFF by default''',
  'SELECT 1');
PREPARE cm_topic_stmt FROM @cm_topic_sql;
EXECUTE cm_topic_stmt;
DEALLOCATE PREPARE cm_topic_stmt;

CREATE TABLE IF NOT EXISTS `city_district` (
  `board_id` varchar(96) NOT NULL,
  `district_id` varchar(64) NOT NULL,
  `name` varchar(64) NOT NULL,
  `color_token` varchar(32) NOT NULL DEFAULT 'district.c1',
  `polygon_json` json DEFAULT NULL,
  `ownable_count` int NOT NULL DEFAULT 0,
  `safety_blocked` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`board_id`, `district_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly districts (colour groups)';

CREATE TABLE IF NOT EXISTS `city_monopoly_quote` (
  `quote_id` char(36) NOT NULL,
  `member_id` bigint NOT NULL,
  `kind` varchar(16) NOT NULL COMMENT 'CLAIM/UPGRADE/CHALLENGE/SCOUT',
  `target_key` varchar(160) NOT NULL COMMENT 'territory id or poi/board/property key',
  `cost` bigint NOT NULL DEFAULT 0,
  `payload_json` json DEFAULT NULL,
  `rules_hash` char(64) NOT NULL,
  `quote_hash` char(64) NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  `used_at` datetime(3) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`quote_id`),
  KEY `idx_city_quote_member` (`member_id`, `kind`, `create_time`),
  KEY `idx_city_quote_target` (`target_key`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly quotes (300 s)';

CREATE TABLE IF NOT EXISTS `city_monopoly_receipt` (
  `member_id` bigint NOT NULL,
  `request_id` varchar(96) NOT NULL,
  `action` varchar(24) NOT NULL,
  `body_hash` char(64) NOT NULL,
  `response_json` json NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`, `request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly idempotent command receipts';

CREATE TABLE IF NOT EXISTS `city_points_flow` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `season_id` varchar(96) NOT NULL,
  `delta` bigint NOT NULL COMMENT '积分, signed',
  `state` varchar(12) NOT NULL COMMENT 'PENDING/AVAILABLE/VOID/UNPAID',
  `kind` varchar(16) NOT NULL COMMENT 'STIPEND/YIELD/VISIT/CHANCE/COMP/CLAIM/UPGRADE/CHALLENGE/REFUND/UPKEEP/SHIELD/SCOUT/SHOP/CLAWBACK/TOLL',
  `ref_type` varchar(24) NOT NULL,
  `ref_id` varchar(96) NOT NULL,
  `points_business_key` varchar(128) DEFAULT NULL COMMENT 'ums_member_points_detail.reward_business_key once written',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `settled_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_city_points_flow` (`member_id`, `kind`, `ref_type`, `ref_id`),
  KEY `idx_city_points_flow_season` (`season_id`, `member_id`, `state`),
  KEY `idx_city_points_flow_state` (`state`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly 积分 movement audit (not a balance)';

CREATE TABLE IF NOT EXISTS `city_member_season` (
  `member_id` bigint NOT NULL,
  `season_id` varchar(96) NOT NULL,
  `earned` bigint NOT NULL DEFAULT 0,
  `spent` bigint NOT NULL DEFAULT 0,
  `debt` bigint NOT NULL DEFAULT 0 COMMENT 'unpaid upkeep/clawback offset from later income',
  `confirmed_rent` bigint NOT NULL DEFAULT 0,
  `first_claims` int NOT NULL DEFAULT 0,
  `takeovers` int NOT NULL DEFAULT 0,
  `defenses` int NOT NULL DEFAULT 0,
  `holding_score` decimal(12,2) NOT NULL DEFAULT 0,
  `stipend_at` datetime(3) DEFAULT NULL,
  `score_cleared` tinyint NOT NULL DEFAULT 0,
  `version` bigint NOT NULL DEFAULT 1,
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`, `season_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly per-member season stats (积分 denominated)';

CREATE TABLE IF NOT EXISTS `city_yield_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `board_id` varchar(96) DEFAULT NULL,
  `season_id` varchar(96) NOT NULL,
  `territory_id` char(36) NOT NULL,
  `holder_member_id` bigint NOT NULL,
  `visitor_member_id` bigint NOT NULL,
  `checkin_ref` varchar(128) NOT NULL,
  `board_day` date NOT NULL,
  `base` int NOT NULL,
  `mult_json` json DEFAULT NULL,
  `raw` bigint NOT NULL,
  `granted` bigint NOT NULL,
  `visitor_reward` bigint NOT NULL DEFAULT 0,
  `cap_reason` varchar(32) DEFAULT NULL,
  `state` varchar(12) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/AVAILABLE/VOID',
  `risk_flags` varchar(255) DEFAULT NULL,
  `device_key` varchar(128) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `settled_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_city_yield_visit_day` (`territory_id`, `visitor_member_id`, `board_day`),
  KEY `idx_city_yield_holder_day` (`holder_member_id`, `board_day`),
  KEY `idx_city_yield_visitor_day` (`visitor_member_id`, `board_day`),
  KEY `idx_city_yield_state` (`state`, `board_day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly rent events (pending -> nightly settle)';

CREATE TABLE IF NOT EXISTS `city_claim_evidence` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `board_id` varchar(96) DEFAULT NULL,
  `target_key` varchar(160) NOT NULL,
  `territory_id` char(36) DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `kind` varchar(12) NOT NULL COMMENT 'CLAIM/UPGRADE/CHALLENGE',
  `photo_asset_id` varchar(500) DEFAULT NULL,
  `phash` bigint DEFAULT NULL,
  `captured_at` datetime(3) DEFAULT NULL,
  `lat` decimal(16,12) DEFAULT NULL,
  `lng` decimal(16,12) DEFAULT NULL,
  `h_acc` double DEFAULT NULL,
  `sample_count` int NOT NULL DEFAULT 0,
  `dwell_s` int NOT NULL DEFAULT 0,
  `max_speed_mps` double NOT NULL DEFAULT 0,
  `simulated` tinyint NOT NULL DEFAULT 0,
  `attest_ok` tinyint NOT NULL DEFAULT 0,
  `merchant_qr` tinyint NOT NULL DEFAULT 0,
  `risk_score` decimal(4,3) NOT NULL DEFAULT 0,
  `verdict` varchar(16) NOT NULL COMMENT 'PASS/REJECTED/REVIEW',
  `reasons` varchar(500) DEFAULT NULL,
  `reviewer_id` bigint DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_city_evidence_member` (`member_id`, `kind`, `created_at`),
  KEY `idx_city_evidence_target` (`target_key`, `created_at`),
  KEY `idx_city_evidence_verdict` (`verdict`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly claim/upgrade/challenge evidence';

CREATE TABLE IF NOT EXISTS `city_challenge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` varchar(96) NOT NULL,
  `board_id` varchar(96) DEFAULT NULL,
  `season_id` varchar(96) NOT NULL,
  `territory_id` char(36) NOT NULL,
  `challenger_id` bigint NOT NULL,
  `defender_id` bigint NOT NULL,
  `fee` bigint NOT NULL,
  `dc` int NOT NULL,
  `roll` int NOT NULL,
  `nat` tinyint NOT NULL DEFAULT 0,
  `reroll_used` tinyint NOT NULL DEFAULT 0,
  `mods_json` json DEFAULT NULL,
  `total` int NOT NULL,
  `outcome` varchar(16) NOT NULL COMMENT 'WIN/LOSS/TASK_FAILED',
  `fee_split_json` json DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_city_challenge_req` (`challenger_id`, `request_id`),
  KEY `idx_city_challenge_terr` (`territory_id`, `created_at`),
  KEY `idx_city_challenge_defender` (`defender_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly d20 challenges';

CREATE TABLE IF NOT EXISTS `city_season` (
  `season_id` varchar(96) NOT NULL,
  `region_id` varchar(96) NOT NULL DEFAULT 'default',
  `starts_at` datetime(3) NOT NULL,
  `ends_at` datetime(3) NOT NULL,
  `phase` varchar(12) NOT NULL DEFAULT 'OPENING',
  `status` varchar(12) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/FROZEN/SETTLED',
  `config_hash` char(64) DEFAULT NULL,
  `carry_rate` decimal(4,2) NOT NULL DEFAULT 0.20,
  `carry_cap` int NOT NULL DEFAULT 300,
  `settled_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`season_id`, `region_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly 28-day seasons';

CREATE TABLE IF NOT EXISTS `city_season_score` (
  `season_id` varchar(96) NOT NULL,
  `scope` varchar(8) NOT NULL COMMENT 'SOLO/TEAM/CLUB',
  `subject_id` varchar(96) NOT NULL,
  `score` decimal(14,2) NOT NULL DEFAULT 0,
  `rank_no` int DEFAULT NULL,
  `detail_json` json DEFAULT NULL,
  `rewarded_at` datetime(3) DEFAULT NULL,
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`season_id`, `scope`, `subject_id`),
  KEY `idx_city_season_score_rank` (`season_id`, `scope`, `score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly solo/team/club season scores';

CREATE TABLE IF NOT EXISTS `city_abuse_signal` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `kind` varchar(16) NOT NULL COMMENT 'SPOOF/DEVICE/RECIPROCITY/CONCENTRATION/RING/PHOTO/TELEPORT',
  `score` decimal(8,3) NOT NULL DEFAULT 0,
  `window_from` datetime(3) DEFAULT NULL,
  `window_to` datetime(3) DEFAULT NULL,
  `detail_json` json DEFAULT NULL,
  `status` varchar(12) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CONFIRMED/DISMISSED',
  `action` varchar(16) DEFAULT NULL COMMENT 'FLAG/PENALTY/FREEZE/SUSPEND/BAN/SEASON_BAN',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_city_abuse_member` (`member_id`, `kind`, `created_at`),
  KEY `idx_city_abuse_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly anti-abuse signals & review queue';

CREATE TABLE IF NOT EXISTS `city_device_account` (
  `device_key` varchar(128) NOT NULL COMMENT 'IDFV/DeviceCheck hash or mini-program unionid',
  `member_id` bigint NOT NULL,
  `first_seen_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_yield_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`device_key`, `member_id`),
  KEY `idx_city_device_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='City Monopoly device -> account (alt-account rule)';

CREATE TABLE IF NOT EXISTS `member_attribute` (
  `member_id` bigint NOT NULL,
  `attr_key` varchar(16) NOT NULL COMMENT 'explore/stamina/insight/charm/luck/lore',
  `axp` bigint NOT NULL DEFAULT 0,
  `level` tinyint NOT NULL DEFAULT 1,
  `season_axp` bigint NOT NULL DEFAULT 0,
  `today_raw` bigint NOT NULL DEFAULT 0,
  `today_eff` bigint NOT NULL DEFAULT 0,
  `today_day` date DEFAULT NULL,
  `backfilled` tinyint NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`, `attr_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Attributes v2 permanent six attributes';

CREATE TABLE IF NOT EXISTS `member_attribute_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `attr_key` varchar(16) NOT NULL,
  `raw` bigint NOT NULL,
  `eff` bigint NOT NULL,
  `source` varchar(32) NOT NULL,
  `source_id` varchar(128) NOT NULL,
  `day` date NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_attr_source` (`member_id`, `attr_key`, `source`, `source_id`),
  KEY `idx_member_attr_day` (`member_id`, `attr_key`, `day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Attributes v2 AXP entries (idempotent by source)';

CREATE TABLE IF NOT EXISTS `member_title` (
  `member_id` bigint NOT NULL,
  `title_code` varchar(64) NOT NULL,
  `title_name` varchar(64) NOT NULL,
  `category` varchar(8) NOT NULL COMMENT 'ATTR/SEASON/THEME/CLUB',
  `source_id` varchar(96) DEFAULT NULL,
  `obtained_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `equipped` tinyint NOT NULL DEFAULT 0,
  PRIMARY KEY (`member_id`, `title_code`),
  KEY `idx_member_title_equipped` (`member_id`, `equipped`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Attributes v2 / season titles';

CREATE TABLE IF NOT EXISTS `member_archetype` (
  `member_id` bigint NOT NULL,
  `primary_key` varchar(16) DEFAULT NULL,
  `secondary_key` varchar(16) DEFAULT NULL,
  `code` varchar(16) NOT NULL,
  `name` varchar(32) NOT NULL,
  `computed_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Attributes v2 display archetype';

-- Admin-tunable approved defaults (CITY-MONOPOLY-SPEC §6.1). Values mirror
-- chengyinhub-system/src/main/resources/citygame/city-monopoly-defaults.properties (asserted by a unit test).
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 version', 'city.monopoly.version', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.version');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 enabled', 'city.monopoly.enabled', 'true', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.enabled');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 currency', 'city.monopoly.currency', 'POINTS', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.currency');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 timezone', 'city.monopoly.timezone', 'Asia/Shanghai', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.timezone');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 board.mode', 'city.monopoly.board.mode', 'LIVE', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.board.mode');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 board.districts.min', 'city.monopoly.board.districts.min', '6', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.board.districts.min');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 board.districts.max', 'city.monopoly.board.districts.max', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.board.districts.max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 board.properties_per_district.min', 'city.monopoly.board.properties_per_district.min', '4', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.board.properties_per_district.min');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 board.properties_per_district.max', 'city.monopoly.board.properties_per_district.max', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.board.properties_per_district.max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 board.neutral_share.min', 'city.monopoly.board.neutral_share.min', '0.10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.board.neutral_share.min');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 board.neutral_share.max', 'city.monopoly.board.neutral_share.max', '0.20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.board.neutral_share.max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 board.fog_gate', 'city.monopoly.board.fog_gate', 'require_lit_tile', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.board.fog_gate');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T1.claim_cost', 'city.monopoly.tiers.T1.claim_cost', '60', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T1.claim_cost');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T1.base_yield', 'city.monopoly.tiers.T1.base_yield', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T1.base_yield');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T1.expected_visits_launch', 'city.monopoly.tiers.T1.expected_visits_launch', '0.6', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T1.expected_visits_launch');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T2.claim_cost', 'city.monopoly.tiers.T2.claim_cost', '120', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T2.claim_cost');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T2.base_yield', 'city.monopoly.tiers.T2.base_yield', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T2.base_yield');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T2.expected_visits_launch', 'city.monopoly.tiers.T2.expected_visits_launch', '1.2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T2.expected_visits_launch');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T3.claim_cost', 'city.monopoly.tiers.T3.claim_cost', '240', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T3.claim_cost');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T3.base_yield', 'city.monopoly.tiers.T3.base_yield', '8', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T3.base_yield');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T3.expected_visits_launch', 'city.monopoly.tiers.T3.expected_visits_launch', '2.4', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T3.expected_visits_launch');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T4.claim_cost', 'city.monopoly.tiers.T4.claim_cost', '400', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T4.claim_cost');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T4.base_yield', 'city.monopoly.tiers.T4.base_yield', '12', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T4.base_yield');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 tiers.T4.expected_visits_launch', 'city.monopoly.tiers.T4.expected_visits_launch', '4.0', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.tiers.T4.expected_visits_launch');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 upgrade.cost_factor', 'city.monopoly.upgrade.cost_factor', '0.5,0.8,1.2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.upgrade.cost_factor');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 upgrade.yield_multiplier', 'city.monopoly.upgrade.yield_multiplier', '1.0,1.4,1.8,2.4', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.upgrade.yield_multiplier');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 upgrade.dc_bonus_per_level', 'city.monopoly.upgrade.dc_bonus_per_level', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.upgrade.dc_bonus_per_level');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 upgrade.per_property_per_day', 'city.monopoly.upgrade.per_property_per_day', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.upgrade.per_property_per_day');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 upgrade.per_member_per_day', 'city.monopoly.upgrade.per_member_per_day', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.upgrade.per_member_per_day');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.radius_m', 'city.monopoly.claim.radius_m', '40', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.radius_m');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.max_h_accuracy_m', 'city.monopoly.claim.max_h_accuracy_m', '25', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.max_h_accuracy_m');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.merchant_qr_fallback.enabled', 'city.monopoly.claim.merchant_qr_fallback.enabled', 'true', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.merchant_qr_fallback.enabled');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.merchant_qr_fallback.max_h_accuracy_m', 'city.monopoly.claim.merchant_qr_fallback.max_h_accuracy_m', '65', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.merchant_qr_fallback.max_h_accuracy_m');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.dwell.min_samples', 'city.monopoly.claim.dwell.min_samples', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.dwell.min_samples');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.dwell.min_span_s', 'city.monopoly.claim.dwell.min_span_s', '60', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.dwell.min_span_s');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.dwell.window_s', 'city.monopoly.claim.dwell.window_s', '300', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.dwell.window_s');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.dwell.max_spread_m', 'city.monopoly.claim.dwell.max_spread_m', '80', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.dwell.max_spread_m');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.max_speed_mps', 'city.monopoly.claim.max_speed_mps', '35', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.max_speed_mps');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.teleport_audit_mps', 'city.monopoly.claim.teleport_audit_mps', '50', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.teleport_audit_mps');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.reject_simulated_location', 'city.monopoly.claim.reject_simulated_location', 'true', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.reject_simulated_location');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.require_app_attest', 'city.monopoly.claim.require_app_attest', 'true', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.require_app_attest');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.photo.in_app_camera_only', 'city.monopoly.claim.photo.in_app_camera_only', 'true', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.photo.in_app_camera_only');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.photo.max_capture_age_s', 'city.monopoly.claim.photo.max_capture_age_s', '120', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.photo.max_capture_age_s');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.photo.max_capture_to_request_m', 'city.monopoly.claim.photo.max_capture_to_request_m', '30', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.photo.max_capture_to_request_m');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.photo.phash_reuse_self_max_hamming', 'city.monopoly.claim.photo.phash_reuse_self_max_hamming', '8', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.photo.phash_reuse_self_max_hamming');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.photo.phash_copy_other_max_hamming', 'city.monopoly.claim.photo.phash_copy_other_max_hamming', '4', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.photo.phash_copy_other_max_hamming');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.photo.screen_recapture_reject_score', 'city.monopoly.claim.photo.screen_recapture_reject_score', '0.7', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.photo.screen_recapture_reject_score');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.photo.human_review_sample_rate', 'city.monopoly.claim.photo.human_review_sample_rate', '0.05', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.photo.human_review_sample_rate');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.photo.human_review_risk_threshold', 'city.monopoly.claim.photo.human_review_risk_threshold', '0.6', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.photo.human_review_risk_threshold');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.min_account_age_days', 'city.monopoly.claim.min_account_age_days', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.min_account_age_days');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.min_account_level', 'city.monopoly.claim.min_account_level', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.min_account_level');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.hours.open', 'city.monopoly.claim.hours.open', '06:00', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.hours.open');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.hours.close', 'city.monopoly.claim.hours.close', '23:00', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.hours.close');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.per_member_per_day', 'city.monopoly.claim.per_member_per_day', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.per_member_per_day');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.per_property_failed_per_day', 'city.monopoly.claim.per_property_failed_per_day', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.per_property_failed_per_day');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.reclaim_after_loss_cooldown_h', 'city.monopoly.claim.reclaim_after_loss_cooldown_h', '24', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.reclaim_after_loss_cooldown_h');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.holding_limit.base', 'city.monopoly.claim.holding_limit.base', '9', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.holding_limit.base');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.holding_limit.explore_bonus_levels', 'city.monopoly.claim.holding_limit.explore_bonus_levels', '15,30,45', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.holding_limit.explore_bonus_levels');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.holding_limit.max', 'city.monopoly.claim.holding_limit.max', '12', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.holding_limit.max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.visit_radius_m', 'city.monopoly.yield.visit_radius_m', '50', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.visit_radius_m');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.visit_max_h_accuracy_m', 'city.monopoly.yield.visit_max_h_accuracy_m', '35', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.visit_max_h_accuracy_m');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.multiplier_cap', 'city.monopoly.yield.multiplier_cap', '4.0', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.multiplier_cap');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.set_bonus.majority_share', 'city.monopoly.yield.set_bonus.majority_share', '0.5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.set_bonus.majority_share');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.set_bonus.majority', 'city.monopoly.yield.set_bonus.majority', '1.2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.set_bonus.majority');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.set_bonus.monopoly', 'city.monopoly.yield.set_bonus.monopoly', '1.5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.set_bonus.monopoly');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.time_bands', 'city.monopoly.yield.time_bands', '06:00-10:00@1.1,10:00-17:00@1.0,17:00-21:00@1.2,21:00-23:00@1.0,23:00-06:00@0.0', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.time_bands');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.weather.light_rain', 'city.monopoly.yield.weather.light_rain', '1.2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.weather.light_rain');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.weather.moderate_rain', 'city.monopoly.yield.weather.moderate_rain', '1.2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.weather.moderate_rain');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.weather.red_alert', 'city.monopoly.yield.weather.red_alert', 'SAFETY_BLOCKED', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.weather.red_alert');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.event_multiplier_max', 'city.monopoly.yield.event_multiplier_max', '2.0', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.event_multiplier_max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.event_multiplier', 'city.monopoly.yield.event_multiplier', '1.0', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.event_multiplier');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.city_launch_multiplier', 'city.monopoly.yield.city_launch_multiplier', '1.0', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.city_launch_multiplier');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.team_bonus.min_team_props_in_district', 'city.monopoly.yield.team_bonus.min_team_props_in_district', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.team_bonus.min_team_props_in_district');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.team_bonus.m', 'city.monopoly.yield.team_bonus.m', '1.1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.team_bonus.m');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.charm_bonus_per_level', 'city.monopoly.yield.charm_bonus_per_level', '0.002', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.charm_bonus_per_level');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.owner_daily.soft_threshold', 'city.monopoly.yield.owner_daily.soft_threshold', '200', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.owner_daily.soft_threshold');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.owner_daily.charm_threshold_per_level', 'city.monopoly.yield.owner_daily.charm_threshold_per_level', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.owner_daily.charm_threshold_per_level');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.owner_daily.charm_threshold_max', 'city.monopoly.yield.owner_daily.charm_threshold_max', '100', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.owner_daily.charm_threshold_max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.owner_daily.over_rate', 'city.monopoly.yield.owner_daily.over_rate', '0.5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.owner_daily.over_rate');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.owner_daily.over_max', 'city.monopoly.yield.owner_daily.over_max', '100', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.owner_daily.over_max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.per_property_daily_visits', 'city.monopoly.yield.per_property_daily_visits', '20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.per_property_daily_visits');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.visitor_per_property_per_day', 'city.monopoly.yield.visitor_per_property_per_day', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.visitor_per_property_per_day');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.pair_cap.per_property_days', 'city.monopoly.yield.pair_cap.per_property_days', '7', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.pair_cap.per_property_days');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.pair_cap.per_owner_per_7d', 'city.monopoly.yield.pair_cap.per_owner_per_7d', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.pair_cap.per_owner_per_7d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.visitor_daily_yielding_checkins', 'city.monopoly.yield.visitor_daily_yielding_checkins', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.visitor_daily_yielding_checkins');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.visitor_reward', 'city.monopoly.yield.visitor_reward', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.visitor_reward');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.settle.mode', 'city.monopoly.yield.settle.mode', 'pending_then_nightly', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.settle.mode');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 yield.settle.nightly_at', 'city.monopoly.yield.settle.nightly_at', '03:00', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.yield.settle.nightly_at');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 chance.reward', 'city.monopoly.chance.reward', '15', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.chance.reward');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 chance.per_day', 'city.monopoly.chance.per_day', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.chance.per_day');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 chance.task_table_d6', 'city.monopoly.chance.task_table_d6', 'PHOTO,PHOTO,CITY_QUIZ,CITY_QUIZ,FIND,CHOOSE', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.chance.task_table_d6');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 protection.after_claim_h', 'city.monopoly.protection.after_claim_h', '24', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.protection.after_claim_h');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 protection.after_defense_h', 'city.monopoly.protection.after_defense_h', '12', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.protection.after_defense_h');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 protection.after_takeover_h', 'city.monopoly.protection.after_takeover_h', '24', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.protection.after_takeover_h');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 protection.shield.cost', 'city.monopoly.protection.shield.cost', '60', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.protection.shield.cost');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 protection.shield.hours', 'city.monopoly.protection.shield.hours', '12', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.protection.shield.hours');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 protection.shield.per_property_cooldown_d', 'city.monopoly.protection.shield.per_property_cooldown_d', '7', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.protection.shield.per_property_cooldown_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 protection.shield.max_total_h', 'city.monopoly.protection.shield.max_total_h', '48', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.protection.shield.max_total_h');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.fee.rate_of_invested', 'city.monopoly.challenge.fee.rate_of_invested', '0.5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.fee.rate_of_invested');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.fee.min', 'city.monopoly.challenge.fee.min', '30', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.fee.min');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.on_win.defender_comp', 'city.monopoly.challenge.on_win.defender_comp', '0.40', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.on_win.defender_comp');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.on_win.burn', 'city.monopoly.challenge.on_win.burn', '0.60', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.on_win.burn');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.on_win.level_drop', 'city.monopoly.challenge.on_win.level_drop', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.on_win.level_drop');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.on_loss.defender_comp', 'city.monopoly.challenge.on_loss.defender_comp', '0.30', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.on_loss.defender_comp');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.on_loss.burn', 'city.monopoly.challenge.on_loss.burn', '0.70', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.on_loss.burn');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.on_loss.protection_h', 'city.monopoly.challenge.on_loss.protection_h', '12', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.on_loss.protection_h');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.on_loss.challenger_cooldown_h', 'city.monopoly.challenge.on_loss.challenger_cooldown_h', '24', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.on_loss.challenger_cooldown_h');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.task_fail_refund', 'city.monopoly.challenge.task_fail_refund', '0.5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.task_fail_refund');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.dc.base', 'city.monopoly.challenge.dc.base', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.dc.base');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.dc.per_level', 'city.monopoly.challenge.dc.per_level', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.dc.per_level');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.dc.owner_recent_visit_h', 'city.monopoly.challenge.dc.owner_recent_visit_h', '72', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.dc.owner_recent_visit_h');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.dc.recent_visit', 'city.monopoly.challenge.dc.recent_visit', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.dc.recent_visit');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.dc.majority', 'city.monopoly.challenge.dc.majority', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.dc.majority');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.dc.monopoly', 'city.monopoly.challenge.dc.monopoly', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.dc.monopoly');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.dc.weathered', 'city.monopoly.challenge.dc.weathered', '-2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.dc.weathered');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.dc.max', 'city.monopoly.challenge.dc.max', '20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.dc.max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.mods.task_pass', 'city.monopoly.challenge.mods.task_pass', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.mods.task_pass');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.mods.task_perfect', 'city.monopoly.challenge.mods.task_perfect', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.mods.task_perfect');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.mods.insight', 'city.monopoly.challenge.mods.insight', '15:1,35:2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.mods.insight');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.mods.warmup_km', 'city.monopoly.challenge.mods.warmup_km', '1.0', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.mods.warmup_km');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.mods.warmup_window_min', 'city.monopoly.challenge.mods.warmup_window_min', '60', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.mods.warmup_window_min');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.mods.warmup_min_stamina', 'city.monopoly.challenge.mods.warmup_min_stamina', '20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.mods.warmup_min_stamina');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.mods.assist_per_member', 'city.monopoly.challenge.mods.assist_per_member', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.mods.assist_per_member');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.mods.assist_max', 'city.monopoly.challenge.mods.assist_max', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.mods.assist_max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.mods.assist_radius_m', 'city.monopoly.challenge.mods.assist_radius_m', '100', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.mods.assist_radius_m');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.mods.cap', 'city.monopoly.challenge.mods.cap', '6', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.mods.cap');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.nat20_wins', 'city.monopoly.challenge.nat20_wins', 'true', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.nat20_wins');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.nat1_loses', 'city.monopoly.challenge.nat1_loses', 'true', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.nat1_loses');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.per_member_per_day', 'city.monopoly.challenge.per_member_per_day', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.per_member_per_day');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.per_property_per_day', 'city.monopoly.challenge.per_property_per_day', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.per_property_per_day');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.revenge_discount.rate', 'city.monopoly.challenge.revenge_discount.rate', '0.5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.revenge_discount.rate');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.revenge_discount.window_h', 'city.monopoly.challenge.revenge_discount.window_h', '24', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.revenge_discount.window_h');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 challenge.scout_cost', 'city.monopoly.challenge.scout_cost', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.challenge.scout_cost');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 decay.weathered_after_d', 'city.monopoly.decay.weathered_after_d', '7', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.decay.weathered_after_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 decay.weathered.yield', 'city.monopoly.decay.weathered.yield', '0.5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.decay.weathered.yield');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 decay.weathered.dc', 'city.monopoly.decay.weathered.dc', '-2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.decay.weathered.dc');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 decay.level_drop_at_d', 'city.monopoly.decay.level_drop_at_d', '14', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.decay.level_drop_at_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 decay.level_drop_every_d', 'city.monopoly.decay.level_drop_every_d', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.decay.level_drop_every_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 decay.release_at_d', 'city.monopoly.decay.release_at_d', '21', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.decay.release_at_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 decay.reminders_d', 'city.monopoly.decay.reminders_d', '5,12,19', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.decay.reminders_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 decay.lore_grace_days', 'city.monopoly.decay.lore_grace_days', '20:1,40:2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.decay.lore_grace_days');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 upkeep.rate_of_invested_per_day', 'city.monopoly.upkeep.rate_of_invested_per_day', '0.02', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.upkeep.rate_of_invested_per_day');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 upkeep.min_per_property', 'city.monopoly.upkeep.min_per_property', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.upkeep.min_per_property');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 upkeep.lore_discount', 'city.monopoly.upkeep.lore_discount', '25:0.05,45:0.10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.upkeep.lore_discount');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.schedule', 'city.monopoly.dice_event.schedule', 'SAT 14:00-18:00', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.schedule');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.faces', 'city.monopoly.dice_event.faces', '1,2,3,4,5,6', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.faces');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.weights', 'city.monopoly.dice_event.weights', '3,3,3,2,2,1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.weights');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.actions_per_round', 'city.monopoly.dice_event.actions_per_round', '6', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.actions_per_round');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.max_rounds', 'city.monopoly.dice_event.max_rounds', '4', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.max_rounds');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.team.min', 'city.monopoly.dice_event.team.min', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.team.min');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.team.max', 'city.monopoly.dice_event.team.max', '4', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.team.max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.track.min', 'city.monopoly.dice_event.track.min', '12', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.track.min');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.track.max', 'city.monopoly.dice_event.track.max', '20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.track.max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.track.hop_m.min', 'city.monopoly.dice_event.track.hop_m.min', '150', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.track.hop_m.min');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.track.hop_m.max', 'city.monopoly.dice_event.track.hop_m.max', '400', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.track.hop_m.max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.holding_yield', 'city.monopoly.dice_event.holding_yield', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.holding_yield');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.goal_score', 'city.monopoly.dice_event.goal_score', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.goal_score');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.toll', 'city.monopoly.dice_event.toll', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.toll');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.season_weight', 'city.monopoly.dice_event.season_weight', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.season_weight');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.rewards_xp.top3', 'city.monopoly.dice_event.rewards_xp.top3', '150', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.rewards_xp.top3');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 dice_event.rewards_xp.all', 'city.monopoly.dice_event.rewards_xp.all', '50', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.dice_event.rewards_xp.all');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 rerolls.luck_levels', 'city.monopoly.rerolls.luck_levels', '10:1,25:2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.rerolls.luck_levels');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.length_d', 'city.monopoly.season.length_d', '28', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.length_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.epoch', 'city.monopoly.season.epoch', '2026-11-09', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.epoch');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.start', 'city.monopoly.season.start', 'MON 10:00', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.start');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.freeze', 'city.monopoly.season.freeze', 'SUN 22:00', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.freeze');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.id_format', 'city.monopoly.season.id_format', 's{YYYY}w{ISOWEEK}', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.id_format');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.stipend', 'city.monopoly.season.stipend', '300', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.stipend');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.phases.opening_d', 'city.monopoly.season.phases.opening_d', '1,3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.phases.opening_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.phases.finale_d', 'city.monopoly.season.phases.finale_d', '22,28', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.phases.finale_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.phases.finale_challenge_discount', 'city.monopoly.season.phases.finale_challenge_discount', '0.25', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.phases.finale_challenge_discount');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.phases.finale_claim_protection_h', 'city.monopoly.season.phases.finale_claim_protection_h', '12', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.phases.finale_claim_protection_h');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.reset.properties', 'city.monopoly.season.reset.properties', 'AVAILABLE', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.reset.properties');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.reset.carry_rate', 'city.monopoly.season.reset.carry_rate', '0.2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.reset.carry_rate');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.reset.carry_cap', 'city.monopoly.season.reset.carry_cap', '300', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.reset.carry_cap');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.score.per_first_claim', 'city.monopoly.season.score.per_first_claim', '20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.score.per_first_claim');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.score.per_takeover', 'city.monopoly.season.score.per_takeover', '30', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.score.per_takeover');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.score.per_defense', 'city.monopoly.season.score.per_defense', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.score.per_defense');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.score.holding_day_weight', 'city.monopoly.season.score.holding_day_weight', 'T1:1,T2:1.5,T3:2,T4:3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.score.holding_day_weight');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.team_score_top_n', 'city.monopoly.season.team_score_top_n', '4', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.team_score_top_n');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.club_score.top_n', 'city.monopoly.season.club_score.top_n', '20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.club_score.top_n');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.club_score.per_district', 'city.monopoly.season.club_score.per_district', '50', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.club_score.per_district');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.leaderboard_window.top', 'city.monopoly.season.leaderboard_window.top', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.leaderboard_window.top');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.leaderboard_window.around_me', 'city.monopoly.season.leaderboard_window.around_me', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.leaderboard_window.around_me');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.rewards.solo', 'city.monopoly.season.rewards.solo', '[{"rank":1,"title":"城主·{city}·S{n}","badge":"SEASON_LORD","xp":3000},{"rankMax":10,"badge":"SEASON_TOP10","xp":1500},{"pctMax":0.01,"badge":"SEASON_GOLD","xp":2000},{"pctMax":0.10,"badge":"SEASON_SILVER","xp":800},{"pctMax":0.50,"xp":300},{"participantMinClaims":1,"badge":"SEASON_PIONEER","xp":200}]', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.rewards.solo');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.rewards.team_top', 'city.monopoly.season.rewards.team_top', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.rewards.team_top');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 season.rewards.club_top', 'city.monopoly.season.rewards.club_top', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.season.rewards.club_top');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 xp.claim', 'city.monopoly.xp.claim', '30', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.xp.claim');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 xp.upgrade', 'city.monopoly.xp.upgrade', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.xp.upgrade');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 xp.takeover_win', 'city.monopoly.xp.takeover_win', '40', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.xp.takeover_win');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 xp.defense_win', 'city.monopoly.xp.defense_win', '20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.xp.defense_win');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 xp.defense_daily_max', 'city.monopoly.xp.defense_daily_max', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.xp.defense_daily_max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.visitor_min_account_age_d', 'city.monopoly.abuse.visitor_min_account_age_d', '7', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.visitor_min_account_age_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.visitor_min_level', 'city.monopoly.abuse.visitor_min_level', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.visitor_min_level');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.visitor_diversity.window_d', 'city.monopoly.abuse.visitor_diversity.window_d', '14', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.visitor_diversity.window_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.visitor_diversity.min_distinct_owners', 'city.monopoly.abuse.visitor_diversity.min_distinct_owners', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.visitor_diversity.min_distinct_owners');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.visitor_diversity.or_min_offboard_checkins', 'city.monopoly.abuse.visitor_diversity.or_min_offboard_checkins', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.visitor_diversity.or_min_offboard_checkins');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.device.max_yielding_accounts_per_30d', 'city.monopoly.abuse.device.max_yielding_accounts_per_30d', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.device.max_yielding_accounts_per_30d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.device.freeze_at_accounts', 'city.monopoly.abuse.device.freeze_at_accounts', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.device.freeze_at_accounts');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.reciprocity.window_d', 'city.monopoly.abuse.reciprocity.window_d', '14', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.reciprocity.window_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.reciprocity.min_each_way', 'city.monopoly.abuse.reciprocity.min_each_way', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.reciprocity.min_each_way');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.reciprocity.zero_weight_d', 'city.monopoly.abuse.reciprocity.zero_weight_d', '14', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.reciprocity.zero_weight_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.concentration.window_d', 'city.monopoly.abuse.concentration.window_d', '7', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.concentration.window_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.concentration.min_yield', 'city.monopoly.abuse.concentration.min_yield', '300', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.concentration.min_yield');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.concentration.top3_share', 'city.monopoly.abuse.concentration.top3_share', '0.6', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.concentration.top3_share');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.concentration.hhi', 'city.monopoly.abuse.concentration.hhi', '0.35', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.concentration.hhi');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.concentration.penalty', 'city.monopoly.abuse.concentration.penalty', '0.5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.concentration.penalty');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.concentration.penalty_d', 'city.monopoly.abuse.concentration.penalty_d', '7', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.concentration.penalty_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.ring.internal_share', 'city.monopoly.abuse.ring.internal_share', '0.8', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.ring.internal_share');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.ring.min_members', 'city.monopoly.abuse.ring.min_members', '4', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.ring.min_members');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.spoof.flags_window_d', 'city.monopoly.abuse.spoof.flags_window_d', '30', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.spoof.flags_window_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.spoof.flags_for_ban', 'city.monopoly.abuse.spoof.flags_for_ban', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.spoof.flags_for_ban');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.spoof.ban_d', 'city.monopoly.abuse.spoof.ban_d', '7', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.spoof.ban_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.spoof.repeat', 'city.monopoly.abuse.spoof.repeat', 'SEASON_BAN', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.spoof.repeat');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 abuse.staff_cannot_claim_own_store', 'city.monopoly.abuse.staff_cannot_claim_own_store', 'true', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.abuse.staff_cannot_claim_own_store');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 kpi_alerts.median_balance_over_t2_cost', 'city.monopoly.kpi_alerts.median_balance_over_t2_cost', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.kpi_alerts.median_balance_over_t2_cost');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 kpi_alerts.sink_source_ratio_min', 'city.monopoly.kpi_alerts.sink_source_ratio_min', '0.6', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.kpi_alerts.sink_source_ratio_min');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 kpi_alerts.sink_source_window_d', 'city.monopoly.kpi_alerts.sink_source_window_d', '7', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.kpi_alerts.sink_source_window_d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 kpi_alerts.hardcore_p95_balance', 'city.monopoly.kpi_alerts.hardcore_p95_balance', '1500', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.kpi_alerts.hardcore_p95_balance');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 kpi_alerts.max_price_change_per_season', 'city.monopoly.kpi_alerts.max_price_change_per_season', '0.2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.kpi_alerts.max_price_change_per_season');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 shop.shield', 'city.monopoly.shop.shield', '60', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.shop.shield');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 shop.scout', 'city.monopoly.shop.scout', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.shop.scout');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 shop.flag_skin', 'city.monopoly.shop.flag_skin', '300,600,1200', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.shop.flag_skin');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 shop.inscription', 'city.monopoly.shop.inscription', '50', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.shop.inscription');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 shop.district_banner', 'city.monopoly.shop.district_banner', '800', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.shop.district_banner');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 keys', 'attributes.v2.keys', 'explore,stamina,insight,charm,luck,lore', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.keys');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 curve.a', 'attributes.v2.curve.a', '12', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.curve.a');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 curve.p', 'attributes.v2.curve.p', '1.25', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.curve.p');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 curve.knee', 'attributes.v2.curve.knee', '25', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.curve.knee');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 curve.tail', 'attributes.v2.curve.tail', '1.075', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.curve.tail');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 curve.round_to', 'attributes.v2.curve.round_to', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.curve.round_to');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 curve.max_level', 'attributes.v2.curve.max_level', '50', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.curve.max_level');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 daily_soft_cap.explore', 'attributes.v2.daily_soft_cap.explore', '200', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.daily_soft_cap.explore');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 daily_soft_cap.stamina', 'attributes.v2.daily_soft_cap.stamina', '150', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.daily_soft_cap.stamina');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 daily_soft_cap.insight', 'attributes.v2.daily_soft_cap.insight', '150', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.daily_soft_cap.insight');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 daily_soft_cap.charm', 'attributes.v2.daily_soft_cap.charm', '120', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.daily_soft_cap.charm');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 daily_soft_cap.luck', 'attributes.v2.daily_soft_cap.luck', '40', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.daily_soft_cap.luck');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 daily_soft_cap.lore', 'attributes.v2.daily_soft_cap.lore', '150', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.daily_soft_cap.lore');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 over_soft_rate', 'attributes.v2.over_soft_rate', '0.25', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.over_soft_rate');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 luck_max_effective_level', 'attributes.v2.luck_max_effective_level', '30', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.luck_max_effective_level');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.explore.new_tile', 'attributes.v2.sources.explore.new_tile', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.explore.new_tile');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.explore.new_poi', 'attributes.v2.sources.explore.new_poi', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.explore.new_poi');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.explore.new_district', 'attributes.v2.sources.explore.new_district', '30', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.explore.new_district');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.explore.roam_2km', 'attributes.v2.sources.explore.roam_2km', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.explore.roam_2km');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.stamina.per_km', 'attributes.v2.sources.stamina.per_km', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.stamina.per_km');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.stamina.per_1000_steps', 'attributes.v2.sources.stamina.per_1000_steps', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.stamina.per_1000_steps');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.stamina.steps_daily_max', 'attributes.v2.sources.stamina.steps_daily_max', '20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.stamina.steps_daily_max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.stamina.session_30min', 'attributes.v2.sources.stamina.session_30min', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.stamina.session_30min');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.insight.quiz_first_try', 'attributes.v2.sources.insight.quiz_first_try', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.insight.quiz_first_try');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.insight.accuracy_window', 'attributes.v2.sources.insight.accuracy_window', '20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.insight.accuracy_window');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.insight.accuracy_factor.lt50', 'attributes.v2.sources.insight.accuracy_factor.lt50', '0.5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.insight.accuracy_factor.lt50');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.insight.accuracy_factor.50to80', 'attributes.v2.sources.insight.accuracy_factor.50to80', '1.0', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.insight.accuracy_factor.50to80');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.insight.accuracy_factor.gt80', 'attributes.v2.sources.insight.accuracy_factor.gt80', '1.2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.insight.accuracy_factor.gt80');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.insight.puzzle_no_hint', 'attributes.v2.sources.insight.puzzle_no_hint', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.insight.puzzle_no_hint');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.insight.check_dc13_success', 'attributes.v2.sources.insight.check_dc13_success', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.insight.check_dc13_success');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.insight.photo_approved', 'attributes.v2.sources.insight.photo_approved', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.insight.photo_approved');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.insight.challenge_task_perfect', 'attributes.v2.sources.insight.challenge_task_perfect', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.insight.challenge_task_perfect');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.charm.group_play_2plus', 'attributes.v2.sources.charm.group_play_2plus', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.charm.group_play_2plus');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.charm.club_event', 'attributes.v2.sources.charm.club_event', '20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.charm.club_event');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.charm.stamp_exchange', 'attributes.v2.sources.charm.stamp_exchange', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.charm.stamp_exchange');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.charm.photo_like', 'attributes.v2.sources.charm.photo_like', '1', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.charm.photo_like');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.charm.photo_like_daily_max', 'attributes.v2.sources.charm.photo_like_daily_max', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.charm.photo_like_daily_max');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.charm.invite_reaches_lv3', 'attributes.v2.sources.charm.invite_reaches_lv3', '30', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.charm.invite_reaches_lv3');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.luck.nat20', 'attributes.v2.sources.luck.nat20', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.luck.nat20');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.luck.drop', 'attributes.v2.sources.luck.drop', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.luck.drop');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.luck.chance_task', 'attributes.v2.sources.luck.chance_task', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.luck.chance_task');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.luck.active_day', 'attributes.v2.sources.luck.active_day', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.luck.active_day');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.lore.district_3_checkins', 'attributes.v2.sources.lore.district_3_checkins', '20', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.lore.district_3_checkins');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.lore.new_category_30d', 'attributes.v2.sources.lore.new_category_30d', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.lore.new_category_30d');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.lore.city_quiz', 'attributes.v2.sources.lore.city_quiz', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.lore.city_quiz');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.lore.first_claim_in_season', 'attributes.v2.sources.lore.first_claim_in_season', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.lore.first_claim_in_season');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.lore.visitor_yield', 'attributes.v2.sources.lore.visitor_yield', '3', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.lore.visitor_yield');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.lore.owner_maintain', 'attributes.v2.sources.lore.owner_maintain', '2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.lore.owner_maintain');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 sources.lore.upgrade', 'attributes.v2.sources.lore.upgrade', '5', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.sources.lore.upgrade');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 imprint.default_enabled', 'attributes.v2.imprint.default_enabled', 'false', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.imprint.default_enabled');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 imprint.thresholds', 'attributes.v2.imprint.thresholds', '20:1,40:2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.imprint.thresholds');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 imprint.map', 'attributes.v2.imprint.map', 'observe:insight,insight:insight,nerve:explore,body:stamina,agility:stamina,charm:charm,fortune:luck', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.imprint.map');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 backfill.max_level', 'attributes.v2.backfill.max_level', '15', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.backfill.max_level');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 backfill.insight_max_level', 'attributes.v2.backfill.insight_max_level', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.backfill.insight_max_level');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 backfill.charm_max_level', 'attributes.v2.backfill.charm_max_level', '10', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.backfill.charm_max_level');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 title_levels', 'attributes.v2.title_levels', '10,25,40,50', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.title_levels');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 luck_title_levels', 'attributes.v2.luck_title_levels', '10,20,30', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.luck_title_levels');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 archetype.polymath_max_spread', 'attributes.v2.archetype.polymath_max_spread', '0.2', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.archetype.polymath_max_spread');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '属性v2 archetype.recompute_at', 'attributes.v2.archetype.recompute_at', '03:00', 'Y', 'admin', NOW(), 'CITY-MONOPOLY-SPEC 6.1 approved default'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'attributes.v2.archetype.recompute_at');
