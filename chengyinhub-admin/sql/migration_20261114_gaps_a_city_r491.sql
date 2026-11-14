-- App gaps A pass 2: the r491 CITY surfaces on the one server-authoritative board of 20261113_gaps_a_city_game.
-- Additive: three guarded nullable/defaulted columns on unreleased city_game_* tables, plus new tables. One board,
-- one lifecycle: OPEN accepts actions; FROZEN is the settled, read-only board (see docs/delivery/app-gaps-a/MAPPING.md).

-- city_game_point: the merchant and public POI behind a reviewed map point, and the SLOT (ownership) version the
-- ordered map stream counts on.
SET @cg_merchant_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='city_game_point' AND column_name='merchant_id');
SET @cg_merchant_sql := IF(@cg_merchant_exists=0,
  'ALTER TABLE city_game_point ADD COLUMN merchant_id bigint DEFAULT NULL COMMENT ''mms_merchant.id operating this point'', ADD COLUMN place_id bigint DEFAULT NULL COMMENT ''roam_poi.id'', ADD COLUMN slot_version bigint NOT NULL DEFAULT 0 COMMENT ''ownership version for the ordered map''',
  'SELECT 1');
PREPARE cg_merchant_stmt FROM @cg_merchant_sql;
EXECUTE cg_merchant_stmt;
DEALLOCATE PREPARE cg_merchant_stmt;

SET @cg_face_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='city_game_roll' AND column_name='face_index');
SET @cg_face_sql := IF(@cg_face_exists=0,
  'ALTER TABLE city_game_roll ADD COLUMN face_index int DEFAULT NULL COMMENT ''index into the published dice table'', ADD COLUMN result_json json DEFAULT NULL COMMENT ''r491 action-budget result (BUDGET rolls only)''',
  'SELECT 1');
PREPARE cg_face_stmt FROM @cg_face_sql;
EXECUTE cg_face_stmt;
DEALLOCATE PREPARE cg_face_stmt;

-- Original-request receipts for every r491 command domain (action-budget, acquisition, arrival-evidence,
-- neutral-tasks, onsite-tasks, merchant-operations): the exact reply, stored once with the effect it reports.
CREATE TABLE IF NOT EXISTS `city_r491_request` (
  `member_id` bigint NOT NULL,
  `domain` varchar(32) NOT NULL,
  `request_id` varchar(96) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `http_status` int NOT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `body_json` json NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`, `domain`, `request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY r491 original request receipts';

-- Arrival evidence: server challenge → member submits the display code on site → store staff confirms or rejects.
CREATE TABLE IF NOT EXISTS `city_arrival_challenge` (
  `challenge_id` char(36) NOT NULL,
  `request_id` varchar(96) NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `point_id` varchar(96) NOT NULL,
  `member_id` bigint NOT NULL,
  `team_id` char(36) NOT NULL,
  `session_id` char(36) NOT NULL,
  `merchant_id` bigint NOT NULL,
  `place_id` bigint NOT NULL,
  `ordinal` int NOT NULL,
  `display_code` char(32) NOT NULL,
  `state` varchar(12) NOT NULL DEFAULT 'ISSUED' COMMENT 'ISSUED/SUBMITTED/CONFIRMED/REJECTED/EXPIRED',
  `version` bigint NOT NULL DEFAULT 1,
  `expires_at` datetime(3) NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`challenge_id`),
  UNIQUE KEY `uk_city_arrival_ordinal` (`board_id`, `point_id`, `member_id`, `ordinal`),
  KEY `idx_city_arrival_merchant` (`merchant_id`, `state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY arrival challenges';

CREATE TABLE IF NOT EXISTS `city_arrival_evidence` (
  `evidence_id` char(36) NOT NULL,
  `challenge_id` char(36) NOT NULL,
  `request_id` varchar(96) NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `point_id` varchar(96) NOT NULL,
  `member_id` bigint NOT NULL,
  `team_id` char(36) NOT NULL,
  `session_id` char(36) NOT NULL,
  `merchant_id` bigint NOT NULL,
  `place_id` bigint NOT NULL,
  `reviewer_member_id` bigint NOT NULL,
  `reviewer_role` varchar(24) NOT NULL,
  `reason` varchar(500) NOT NULL,
  `create_time` datetime(3) NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  `used_by_instance` char(36) DEFAULT NULL COMMENT 'onsite task instance that consumed it (single use)',
  PRIMARY KEY (`evidence_id`),
  UNIQUE KEY `uk_city_arrival_evidence_challenge` (`challenge_id`),
  KEY `idx_city_arrival_evidence_member` (`board_id`, `member_id`, `point_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY merchant-attested arrival evidence';

-- Published zero-effect tasks (objective pick-one questions). The answer never leaves the server.
CREATE TABLE IF NOT EXISTS `city_task_definition` (
  `board_id` varchar(96) NOT NULL,
  `task_id` varchar(96) NOT NULL,
  `access` varchar(24) NOT NULL COMMENT 'REMOTE_NEUTRAL/ONSITE_ZERO_EFFECT',
  `point_id` varchar(96) DEFAULT NULL COMMENT 'ONSITE only',
  `title` varchar(160) NOT NULL,
  `question` varchar(1000) NOT NULL,
  `options_json` json NOT NULL COMMENT '[{id,label}] 2..8',
  `answer_option_id` varchar(96) NOT NULL,
  `max_attempts` int NOT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  `status` varchar(16) NOT NULL DEFAULT 'PUBLISHED',
  PRIMARY KEY (`board_id`, `task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY neutral and onsite task definitions';

CREATE TABLE IF NOT EXISTS `city_task_instance` (
  `instance_id` char(36) NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `task_id` varchar(96) NOT NULL,
  `access` varchar(24) NOT NULL,
  `owner_key` varchar(64) NOT NULL COMMENT 'team id (neutral) or member id (onsite)',
  `team_id` char(36) NOT NULL,
  `session_id` char(36) NOT NULL,
  `member_id` bigint NOT NULL COMMENT 'accepting member',
  `evidence_id` char(36) DEFAULT NULL,
  `accept_request_id` varchar(96) NOT NULL,
  `accepted_at` datetime(3) NOT NULL,
  `attempts` int NOT NULL DEFAULT 0,
  `status` varchar(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PASSED/EXHAUSTED',
  `version` bigint NOT NULL DEFAULT 1,
  `result_json` json DEFAULT NULL,
  PRIMARY KEY (`instance_id`),
  UNIQUE KEY `uk_city_task_instance_owner` (`board_id`, `task_id`, `owner_key`),
  KEY `idx_city_task_instance_member` (`member_id`, `access`, `instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY task instances';

CREATE TABLE IF NOT EXISTS `city_task_attempt` (
  `attempt_id` char(36) NOT NULL,
  `instance_id` char(36) NOT NULL,
  `request_id` varchar(96) NOT NULL,
  `option_id` varchar(96) NOT NULL,
  `attempt_number` int NOT NULL,
  `passed` tinyint NOT NULL,
  `create_time` datetime(3) NOT NULL,
  `evidence_checked_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`attempt_id`),
  UNIQUE KEY `uk_city_task_attempt_number` (`instance_id`, `attempt_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY task answer attempts';

-- Self-funded acquisition: a quote binds one passed onsite task (and its evidence) to a price; confirming it
-- charges the payer's points and the team's action budget and records a title.
CREATE TABLE IF NOT EXISTS `city_acquisition_quote` (
  `quote_id` char(36) NOT NULL,
  `confirm_request_id` char(36) NOT NULL,
  `request_id` char(36) NOT NULL,
  `member_id` bigint NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `quote_json` json NOT NULL,
  `quote_hash` char(64) NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  `consumed` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`quote_id`),
  UNIQUE KEY `uk_city_acquisition_confirm` (`confirm_request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY acquisition quotes';

CREATE TABLE IF NOT EXISTS `city_acquisition_title` (
  `title_id` char(36) NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `point_id` varchar(96) NOT NULL,
  `owner_team_id` char(36) NOT NULL,
  `payer_member_id` bigint NOT NULL,
  `title_json` json NOT NULL,
  `status` varchar(40) NOT NULL DEFAULT 'ACTIVE_TERM',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`title_id`),
  UNIQUE KEY `uk_city_acquisition_point` (`board_id`, `point_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY acquired point titles';

-- Merchant operation intents (pause / restore) go to platform review; nothing here changes the runtime.
CREATE TABLE IF NOT EXISTS `city_merchant_operation` (
  `merchant_id` bigint NOT NULL,
  `region_id` varchar(96) NOT NULL,
  `version` bigint NOT NULL DEFAULT 0,
  `status` varchar(12) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/IN_REVIEW/APPROVED/REJECTED/APPLIED',
  `effective_state` varchar(12) NOT NULL DEFAULT 'INHERIT' COMMENT 'INHERIT/PAUSED/RESTORED',
  `kind` varchar(20) DEFAULT NULL,
  `request_id` char(36) DEFAULT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `approval_hash` char(64) DEFAULT NULL,
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`merchant_id`, `region_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY merchant operation intents';

-- Player value visits (city-value): begin a visit at an available POI point, then feedback; no points or goals.
CREATE TABLE IF NOT EXISTS `city_value_visit` (
  `visit_id` char(36) NOT NULL,
  `member_id` bigint NOT NULL,
  `request_id` varchar(96) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `point_id` varchar(96) NOT NULL,
  `poi_id` bigint NOT NULL,
  `entered_at` datetime(3) NOT NULL,
  `closed` tinyint NOT NULL DEFAULT 0,
  `feedback_kind` varchar(16) DEFAULT NULL,
  `reason_code` varchar(32) DEFAULT NULL,
  `rating` int DEFAULT NULL,
  PRIMARY KEY (`visit_id`),
  UNIQUE KEY `uk_city_value_request` (`member_id`, `request_id`),
  KEY `idx_city_value_member_point` (`member_id`, `board_id`, `point_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY player value visits';

-- Ordered map: per-point change log (SLOT ownership, MERCHANT, PLATFORM) and per-viewer streams over it.
CREATE TABLE IF NOT EXISTS `city_point_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `board_id` varchar(96) NOT NULL,
  `point_id` varchar(96) NOT NULL,
  `component` varchar(12) NOT NULL COMMENT 'SLOT/MERCHANT/PLATFORM',
  `aggregate_version` bigint NOT NULL,
  `availability` varchar(16) NOT NULL,
  `merchant_version` bigint NOT NULL,
  `platform_version` bigint NOT NULL,
  `holder_team_id` char(36) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_city_point_event_board` (`board_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY point change log';

CREATE TABLE IF NOT EXISTS `city_map_stream` (
  `stream_id` char(36) NOT NULL,
  `member_id` bigint NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `scope_hash` char(64) NOT NULL,
  `team_id` char(36) DEFAULT NULL,
  `base_event_id` bigint NOT NULL,
  `revision` bigint NOT NULL,
  `valid_until` datetime(3) NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`stream_id`),
  KEY `idx_city_map_stream_member` (`member_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY ordered map streams';
