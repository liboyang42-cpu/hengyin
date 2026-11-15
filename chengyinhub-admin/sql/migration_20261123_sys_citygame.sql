-- SYS-citygame: board authoring draft/audit tables, unified dice roll audit columns,
-- Roam + CITY merged territory tables (place<->property link, photo+GPS claim, protection window,
-- season leaderboard, check-in points revenue, plausibility audit), and admin menu.
-- Additive & reversible only; no financial ledger changes.
-- Rollback instructions:
--   DELETE FROM sys_menu WHERE component = 'business/citygame/index';
--   DROP TABLE IF EXISTS roam_territory_audit, roam_territory_checkin, roam_territory;
--   DROP TABLE IF EXISTS city_game_board_audit, city_game_board_draft;

CREATE TABLE IF NOT EXISTS `city_game_board_draft` (
  `draft_id` varchar(96) NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `game_id` varchar(96) NOT NULL DEFAULT 'city-game',
  `region_id` varchar(96) NOT NULL,
  `season_id` varchar(96) NOT NULL,
  `rules_release_id` varchar(96) NOT NULL,
  `rule_version` varchar(64) NOT NULL DEFAULT 'v1',
  `title` varchar(160) NOT NULL,
  `draft_status` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/FROZEN',
  `payload_json` longtext NOT NULL COMMENT 'full editable board JSON (points, track, services, tasks, dice, economy)',
  `published_release_id` bigint DEFAULT NULL,
  `published_rules_hash` char(64) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT 1,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `published_at` datetime(3) DEFAULT NULL,
  `frozen_at` datetime(3) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`draft_id`),
  UNIQUE KEY `uk_city_board_draft_board` (`board_id`),
  KEY `idx_city_board_draft_region` (`region_id`, `draft_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY board admin drafts';

CREATE TABLE IF NOT EXISTS `city_game_board_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `board_id` varchar(96) NOT NULL,
  `action` varchar(32) NOT NULL COMMENT 'DRAFT_SAVE/BOARD_PUBLISHED/BOARD_FROZEN/MERCHANT_OP_APPROVED/MERCHANT_OP_REJECTED/DICE_ROLL/BOARD_TERRITORY_CLAIM',
  `actor_id` bigint DEFAULT NULL,
  `actor_type` varchar(16) NOT NULL DEFAULT 'ADMIN',
  `rules_hash` char(64) DEFAULT NULL,
  `detail_json` json DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_city_board_audit_board` (`board_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY board & ops audit log';

SET @cg_roll_audit_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='city_game_roll' AND column_name='source_path');
SET @cg_roll_audit_sql := IF(@cg_roll_audit_exists=0,
  'ALTER TABLE city_game_roll ADD COLUMN source_path varchar(64) DEFAULT NULL COMMENT ''GAME_ACTION/ACTION_BUDGET/UNIFIED_DICE'', ADD COLUMN random_ticket bigint DEFAULT NULL, ADD COLUMN total_weight bigint DEFAULT NULL, ADD COLUMN previous_position int DEFAULT NULL, ADD COLUMN new_position int DEFAULT NULL, ADD COLUMN landed_property_id varchar(96) DEFAULT NULL, ADD COLUMN before_units int DEFAULT NULL, ADD COLUMN after_units int DEFAULT NULL, ADD COLUMN board_version bigint DEFAULT NULL',
  'SELECT 1');
PREPARE cg_roll_audit_stmt FROM @cg_roll_audit_sql;
EXECUTE cg_roll_audit_stmt;
DEALLOCATE PREPARE cg_roll_audit_stmt;

CREATE TABLE IF NOT EXISTS `roam_territory` (
  `territory_id` char(36) NOT NULL,
  `geohash7` varchar(12) NOT NULL,
  `poi_id` bigint DEFAULT NULL COMMENT 'roam_poi.id / city_game_point.place_id link',
  `board_id` varchar(96) DEFAULT NULL COMMENT 'merged CITY board id',
  `season_id` varchar(96) DEFAULT NULL COMMENT 'merged CITY season id',
  `property_id` varchar(96) DEFAULT NULL COMMENT 'city_game_property.property_id link',
  `point_id` varchar(96) DEFAULT NULL COMMENT 'city_game_point.point_id link',
  `title` varchar(160) NOT NULL,
  `city_code` varchar(24) DEFAULT NULL,
  `latitude` decimal(16,12) NOT NULL,
  `longitude` decimal(16,12) NOT NULL,
  `radius_m` int NOT NULL DEFAULT 80,
  `owner_member_id` bigint NOT NULL,
  `claim_photo_url` varchar(500) NOT NULL,
  `claim_request_id` varchar(96) NOT NULL,
  `checkin_toll_points` bigint NOT NULL DEFAULT 5,
  `owner_revenue_points` bigint NOT NULL DEFAULT 5,
  `revenue_mode` varchar(16) NOT NULL DEFAULT 'POINTS' COMMENT 'POINTS only (owner earns in-game points/coins, never real money)',
  `checkin_count` bigint NOT NULL DEFAULT 0,
  `revenue_total_points` bigint NOT NULL DEFAULT 0,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PAUSED/REVOKED',
  `version` bigint NOT NULL DEFAULT 1,
  `claimed_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `protected_until` datetime(3) DEFAULT NULL,
  `last_checkin_at` datetime(3) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`territory_id`),
  UNIQUE KEY `uk_roam_territory_claim_req` (`owner_member_id`, `claim_request_id`),
  KEY `idx_roam_territory_geo` (`geohash7`, `status`),
  KEY `idx_roam_territory_poi` (`poi_id`, `status`),
  KEY `idx_roam_territory_board_prop` (`board_id`, `property_id`),
  KEY `idx_roam_territory_season` (`season_id`, `status`),
  KEY `idx_roam_territory_owner` (`owner_member_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Merged CITY+Roam territory ownership';

CREATE TABLE IF NOT EXISTS `roam_territory_checkin` (
  `checkin_id` char(36) NOT NULL,
  `territory_id` char(36) NOT NULL,
  `member_id` bigint NOT NULL,
  `owner_member_id` bigint NOT NULL,
  `request_id` varchar(96) NOT NULL,
  `checkin_kind` varchar(24) NOT NULL DEFAULT 'VISIT' COMMENT 'CLAIM/VISIT/ROAM_CHECKIN/CITY_ARRIVAL',
  `latitude` decimal(16,12) NOT NULL,
  `longitude` decimal(16,12) NOT NULL,
  `accuracy_m` double DEFAULT NULL,
  `distance_m` double NOT NULL DEFAULT 0,
  `photo_url` varchar(500) DEFAULT NULL,
  `points_spent` bigint NOT NULL DEFAULT 0,
  `owner_revenue_points` bigint NOT NULL DEFAULT 0,
  `revenue_mode` varchar(16) NOT NULL DEFAULT 'POINTS',
  `plausibility_status` varchar(16) NOT NULL DEFAULT 'PASS' COMMENT 'PASS/FLAGGED/BLOCKED',
  `plausibility_reason` varchar(128) DEFAULT NULL,
  `implied_speed_mps` double DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`checkin_id`),
  UNIQUE KEY `uk_roam_territory_checkin_req` (`member_id`, `request_id`),
  KEY `idx_roam_territory_checkin_terr` (`territory_id`, `create_time`),
  KEY `idx_roam_territory_checkin_member` (`member_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Merged territory claim & visitor check-ins';

CREATE TABLE IF NOT EXISTS `roam_territory_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `territory_id` char(36) DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `action` varchar(24) NOT NULL COMMENT 'CLAIM/CHECKIN/ADMIN_STATUS',
  `verdict` varchar(16) NOT NULL COMMENT 'PASS/FLAGGED/BLOCKED',
  `reason_code` varchar(64) DEFAULT NULL,
  `latitude` decimal(16,12) DEFAULT NULL,
  `longitude` decimal(16,12) DEFAULT NULL,
  `accuracy_m` double DEFAULT NULL,
  `distance_m` double DEFAULT NULL,
  `implied_speed_mps` double DEFAULT NULL,
  `detail_json` json DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_roam_territory_audit_member` (`member_id`, `create_time`),
  KEY `idx_roam_territory_audit_verdict` (`verdict`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Roam territory anti-cheat & plausibility audit log';

-- Admin menu under 漫游探索 (path='roam')
SET @cg_menu_parent := (SELECT menu_id FROM sys_menu WHERE path = 'roam' AND menu_type = 'M' LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 'CITY棋盘与领地', @cg_menu_parent, 4, 'citygame', 'business/citygame/index', 1, 0, 'C', '0', '0', 'business:citygame:list', 'guide', 'admin', NOW(), 'CITY大富翁棋盘编排、审核与领地管理'
FROM DUAL WHERE @cg_menu_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'business/citygame/index');

SET @cg_menu_id := (SELECT menu_id FROM sys_menu WHERE component = 'business/citygame/index' LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 'CITY编辑发布', @cg_menu_id, 1, '#', '', 1, 0, 'F', '0', '0', 'business:citygame:edit', '#', 'admin', NOW(), ''
FROM DUAL WHERE @cg_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = @cg_menu_id AND perms = 'business:citygame:edit');
