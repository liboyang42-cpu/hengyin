-- App gaps A: resident CITY business game (server-authoritative board, rounds, dice, quotes, receipts, fairness).
-- Additive only: new city_game_* tables, no change to existing tables. Board/point/property rows are
-- published by operations; this migration creates no board. Every number the player sees (dice faces and
-- weights, action budget, points cost, service fee, holding limit, protection window) is read from here.

CREATE TABLE IF NOT EXISTS `city_game_board` (
  `board_id` varchar(96) NOT NULL,
  `game_id` varchar(96) NOT NULL,
  `region_id` varchar(96) NOT NULL,
  `season_id` varchar(96) NOT NULL,
  `rules_release_id` varchar(96) NOT NULL,
  `release_id` bigint NOT NULL COMMENT 'numeric frozen release used by team-participation commands',
  `rules_hash` char(64) NOT NULL COMMENT 'sha256 of the frozen rule text; contentHash in commands',
  `rule_version` varchar(64) NOT NULL,
  `lifecycle` varchar(16) NOT NULL COMMENT 'DRAFT/REVIEW/READY/OPEN/FROZEN/CLOSING/CLOSED/ARCHIVED',
  `title` varchar(160) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1 COMMENT 'published configuration revision',
  `board_version` bigint NOT NULL DEFAULT 1 COMMENT 'advances on every applied game effect',
  `event_sequence` bigint NOT NULL DEFAULT 0,
  `dice_id` varchar(96) NOT NULL,
  `dice_faces` varchar(512) NOT NULL COMMENT 'JSON int array',
  `dice_weights` varchar(512) NOT NULL COMMENT 'JSON positive int array, same length as faces',
  `actions_per_round` int NOT NULL,
  `max_rounds` int NOT NULL,
  `roll_cost` int NOT NULL DEFAULT 1,
  `acquisition_cost` int NOT NULL DEFAULT 1,
  `task_cost` int NOT NULL DEFAULT 1,
  `service_cost` int NOT NULL DEFAULT 1,
  `challenge_cost` int NOT NULL DEFAULT 1,
  `acquire_points` bigint NOT NULL COMMENT 'member points to acquire an AVAILABLE property',
  `challenge_points` bigint NOT NULL COMMENT 'member points to challenge a HELD property',
  `holding_yield` int NOT NULL DEFAULT 1 COMMENT 'score per held property at round settlement',
  `goal_score` int NOT NULL DEFAULT 1 COMMENT 'score for a completed neutral goal',
  `holding_limit` int DEFAULT NULL,
  `protection_seconds` int NOT NULL,
  `quote_valid_seconds` int NOT NULL,
  `term_seconds` int NOT NULL,
  `min_members` int NOT NULL,
  `max_members` int NOT NULL,
  `rules_id` varchar(96) NOT NULL,
  `rules_text` varchar(2000) NOT NULL,
  `challenge_rule_id` varchar(96) NOT NULL,
  `protection_text` varchar(2000) NOT NULL,
  `safety_statement` varchar(2000) NOT NULL,
  `valid_from` datetime(3) NOT NULL,
  `valid_until` datetime(3) DEFAULT NULL,
  `fairness_metric_version` varchar(64) DEFAULT NULL,
  `fairness_horizon_seconds` int DEFAULT NULL COMMENT 'NULL keeps the fairness report NOT_COMPUTABLE',
  `fairness_delta_seconds` int DEFAULT NULL,
  `map_datum` varchar(8) NOT NULL DEFAULT 'GCJ02',
  `map_south` decimal(16,12) NOT NULL,
  `map_west` decimal(16,12) NOT NULL,
  `map_north` decimal(16,12) NOT NULL,
  `map_east` decimal(16,12) NOT NULL,
  `map_zoom` int NOT NULL DEFAULT 14,
  `map_min_zoom` int NOT NULL DEFAULT 10,
  `map_max_zoom` int NOT NULL DEFAULT 18,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`board_id`),
  UNIQUE KEY `uk_city_game_board_release` (`release_id`),
  KEY `idx_city_game_board_region` (`region_id`, `lifecycle`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY game board and frozen rules';

CREATE TABLE IF NOT EXISTS `city_game_point` (
  `board_id` varchar(96) NOT NULL,
  `point_id` varchar(96) NOT NULL,
  `title` varchar(160) NOT NULL,
  `latitude` decimal(16,12) NOT NULL,
  `longitude` decimal(16,12) NOT NULL,
  `domain` varchar(24) NOT NULL COMMENT 'MERCHANT/POI/PLATFORM_VIRTUAL',
  `collected_at` datetime(3) NOT NULL,
  `availability` varchar(16) NOT NULL DEFAULT 'AVAILABLE',
  `merchant_version` bigint NOT NULL DEFAULT 0,
  `platform_version` bigint NOT NULL DEFAULT 0,
  `del_flag` tinyint NOT NULL DEFAULT 0,
  PRIMARY KEY (`board_id`, `point_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY reviewed map points';

CREATE TABLE IF NOT EXISTS `city_game_property` (
  `board_id` varchar(96) NOT NULL,
  `property_id` varchar(96) NOT NULL,
  `point_id` varchar(96) NOT NULL,
  `track_index` int NOT NULL COMMENT 'position on the dice track, 0..n-1, unique per board',
  `role` varchar(16) NOT NULL COMMENT 'NEUTRAL/OPERABLE',
  `status` varchar(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/HELD/PROTECTED/PAUSED/SAFETY_BLOCKED',
  `state_version` bigint NOT NULL DEFAULT 1,
  `holder_team_id` varchar(64) DEFAULT NULL,
  `held_since` datetime(3) DEFAULT NULL,
  `protected_until` datetime(3) DEFAULT NULL,
  `challenge_state` varchar(16) DEFAULT NULL,
  PRIMARY KEY (`board_id`, `property_id`),
  UNIQUE KEY `uk_city_game_property_track` (`board_id`, `track_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY board properties (virtual operating rights)';

CREATE TABLE IF NOT EXISTS `city_game_service` (
  `board_id` varchar(96) NOT NULL,
  `service_id` varchar(96) NOT NULL,
  `property_id` varchar(96) NOT NULL,
  `title` varchar(80) NOT NULL,
  `member_points_fee` bigint DEFAULT NULL COMMENT 'NULL = not disclosed, cannot be accepted',
  `status` varchar(16) NOT NULL DEFAULT 'PUBLISHED',
  PRIMARY KEY (`board_id`, `service_id`),
  KEY `idx_city_game_service_property` (`board_id`, `property_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY platform-approved services (toll)';

CREATE TABLE IF NOT EXISTS `city_game_team` (
  `team_id` char(36) NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `captain_member_id` bigint NOT NULL,
  `invite_code` char(36) NOT NULL,
  `state` varchar(16) NOT NULL DEFAULT 'FORMING' COMMENT 'FORMING/LOCKED',
  `version` bigint NOT NULL DEFAULT 1,
  `session_id` char(36) DEFAULT NULL,
  `session_state` varchar(32) DEFAULT NULL COMMENT 'AWAITING_CONFIRMATION/READY',
  `session_version` bigint NOT NULL DEFAULT 0,
  `track_position` int NOT NULL DEFAULT 0,
  `landed_property_id` varchar(96) DEFAULT NULL,
  `score` bigint NOT NULL DEFAULT 0,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`team_id`),
  UNIQUE KEY `uk_city_game_team_invite` (`invite_code`),
  KEY `idx_city_game_team_board` (`board_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY teams';

CREATE TABLE IF NOT EXISTS `city_game_participation` (
  `participation_id` char(36) NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `team_id` char(36) NOT NULL,
  `member_id` bigint NOT NULL,
  `present` tinyint NOT NULL DEFAULT 1,
  `confirmed` tinyint NOT NULL DEFAULT 0,
  `membership_version` bigint NOT NULL DEFAULT 1,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`participation_id`),
  UNIQUE KEY `uk_city_game_participation_member` (`board_id`, `member_id`),
  KEY `idx_city_game_participation_team` (`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY team membership';

CREATE TABLE IF NOT EXISTS `city_game_round` (
  `board_id` varchar(96) NOT NULL,
  `team_id` char(36) NOT NULL,
  `round_seq` bigint NOT NULL,
  `actions_total` int NOT NULL,
  `actions_remaining` int NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/SETTLED',
  `settled_score` bigint DEFAULT NULL,
  `opened_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `settled_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`board_id`, `team_id`, `round_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY team rounds and action budget';

CREATE TABLE IF NOT EXISTS `city_game_roll` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `board_id` varchar(96) NOT NULL,
  `team_id` char(36) NOT NULL,
  `round_seq` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `request_id` varchar(96) NOT NULL,
  `purpose` varchar(16) NOT NULL COMMENT 'MOVE/CHALLENGE/DEFENCE',
  `dice_id` varchar(96) NOT NULL,
  `face` int NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_city_game_roll_team` (`board_id`, `team_id`, `round_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY server dice results (audit)';

CREATE TABLE IF NOT EXISTS `city_game_task_attempt` (
  `board_id` varchar(96) NOT NULL,
  `property_id` varchar(96) NOT NULL,
  `team_id` char(36) NOT NULL,
  `member_id` bigint NOT NULL,
  `request_id` varchar(96) NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`board_id`, `property_id`, `team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY accepted tasks; kept when the property changes hands';

CREATE TABLE IF NOT EXISTS `city_game_quote` (
  `quote_id` char(36) NOT NULL,
  `quote_version` bigint NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `action` varchar(32) NOT NULL,
  `target_id` varchar(96) NOT NULL,
  `payer_member_id` bigint NOT NULL,
  `rule_version` varchar(64) NOT NULL,
  `member_points_cost` bigint NOT NULL,
  `action_cost` int NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  `consumed_request_id` varchar(96) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`quote_id`),
  KEY `idx_city_game_quote_payer` (`payer_member_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY server quotes for point-spending actions';

CREATE TABLE IF NOT EXISTS `city_game_request` (
  `member_id` bigint NOT NULL,
  `domain` varchar(32) NOT NULL COMMENT 'game/team-participation',
  `request_id` varchar(96) NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `action` varchar(32) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `http_status` int NOT NULL,
  `decision` varchar(32) NOT NULL,
  `current_version` bigint NOT NULL,
  `reason_code` varchar(64) DEFAULT NULL,
  `task_attempt_kept` tinyint NOT NULL DEFAULT 0,
  `points_charged` bigint NOT NULL DEFAULT 0,
  `dice_face` int DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`, `domain`, `request_id`),
  KEY `idx_city_game_request_board` (`board_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY idempotent request receipts';

CREATE TABLE IF NOT EXISTS `city_game_event` (
  `board_id` varchar(96) NOT NULL,
  `sequence` bigint NOT NULL,
  `kind` varchar(32) NOT NULL,
  `property_id` varchar(96) DEFAULT NULL,
  `team_id` char(36) DEFAULT NULL,
  `board_version` bigint NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`board_id`, `sequence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY ordered board events (delta stream)';

CREATE TABLE IF NOT EXISTS `city_game_outing` (
  `outing_id` char(36) NOT NULL,
  `board_id` varchar(96) NOT NULL,
  `member_id` bigint NOT NULL,
  `outing_day` date NOT NULL,
  `t0` datetime(3) NOT NULL,
  `action_count` int NOT NULL DEFAULT 1,
  `points_spent` bigint NOT NULL DEFAULT 0,
  `zero_point_blocks` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`outing_id`),
  UNIQUE KEY `uk_city_game_outing_day` (`board_id`, `member_id`, `outing_day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CITY fairness facts: one outing per member per board day';
