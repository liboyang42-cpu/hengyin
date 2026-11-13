-- App gaps A pass 2: player routes the app builds that the backend lacked (profile, play, location share, topics).
-- Additive only: new tables, no change to existing tables or rows.

-- Deregistration result read after apply. Apply revokes every session of the account, so the apply reply carries
-- a one-time-shown result token; only its SHA-256 is stored. The token reads that member's deregistration status
-- and nothing else, and expires 45 days after issue (30-day cooling-off plus the execution batch window).
CREATE TABLE IF NOT EXISTS `account_deregistration_result_token` (
  `token_hash` char(64) NOT NULL,
  `member_id` bigint NOT NULL,
  `request_id` varchar(64) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `expires_at` datetime(3) NOT NULL,
  PRIMARY KEY (`token_hash`),
  KEY `idx_dereg_result_member` (`member_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='deregistration result read tokens (hash only)';

-- City-orientation answer receipts: one stored reply per (member, requestId) around the existing answer path.
CREATE TABLE IF NOT EXISTS `city_orientation_answer` (
  `member_id` bigint NOT NULL,
  `request_id` char(36) NOT NULL,
  `session_id` bigint NOT NULL,
  `node_id` bigint NOT NULL,
  `payload_hash` char(64) NOT NULL,
  `receipt_json` json NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`, `request_id`),
  KEY `idx_city_orientation_session` (`session_id`, `node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='city orientation original answer receipts';

-- Personal location sharing. Retention rule: only the latest observation of an ACTIVE grant is stored; it is
-- deleted when the grant stops, is revoked or expires. Coarse grants store the snapped cell, never the exact fix.
-- Eligibility: owner and recipient follow each other (mutual follow) and a policy row is published.
CREATE TABLE IF NOT EXISTS `location_share_policy` (
  `policy_id` varchar(64) NOT NULL,
  `version` bigint NOT NULL,
  `approval_ref` varchar(128) NOT NULL,
  `mode_choices` json NOT NULL COMMENT '[{id, mode EXACT|COARSE, latitudeStep, longitudeStep}]',
  `duration_choices` json NOT NULL COMMENT '[{id, seconds}]',
  `refresh_after_ms` bigint NOT NULL,
  `stale_after_ms` bigint NOT NULL,
  `display_lease_ms` bigint NOT NULL,
  `owner_lease_ms` bigint NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/RETIRED; one PUBLISHED at a time',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`policy_id`, `version`),
  KEY `idx_location_share_policy_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='personal location share policy (published by operations)';

CREATE TABLE IF NOT EXISTS `location_share_grant` (
  `grant_id` char(36) NOT NULL,
  `owner_id` bigint NOT NULL,
  `request_key` char(36) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `recipient_id` bigint NOT NULL,
  `policy_id` varchar(64) NOT NULL,
  `policy_version` bigint NOT NULL,
  `policy_digest` char(64) NOT NULL,
  `mode_choice_id` varchar(64) NOT NULL,
  `mode` varchar(8) NOT NULL,
  `duration_choice_id` varchar(64) NOT NULL,
  `created_at_ms` bigint NOT NULL,
  `expires_at_ms` bigint NOT NULL,
  `owner_lease_until_ms` bigint NOT NULL,
  `version` bigint NOT NULL DEFAULT 1,
  `watermark` bigint NOT NULL DEFAULT 1,
  `last_sequence` bigint NOT NULL DEFAULT 0,
  `status` varchar(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/STOPPED/REVOKED/EXPIRED',
  `reason` varchar(32) DEFAULT NULL,
  `active_pair` varchar(48) GENERATED ALWAYS AS (IF(`status` = 'ACTIVE', CONCAT(`owner_id`, ':', `recipient_id`), NULL)) STORED,
  PRIMARY KEY (`grant_id`),
  UNIQUE KEY `uk_location_share_request` (`owner_id`, `request_key`),
  UNIQUE KEY `uk_location_share_active_pair` (`active_pair`),
  KEY `idx_location_share_recipient` (`recipient_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='personal location share grants';

CREATE TABLE IF NOT EXISTS `location_share_observation` (
  `grant_id` char(36) NOT NULL,
  `sequence` bigint NOT NULL,
  `observed_at_ms` bigint NOT NULL,
  `latitude` decimal(12,8) NOT NULL,
  `longitude` decimal(12,8) NOT NULL,
  PRIMARY KEY (`grant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='latest observation per active grant only';

CREATE TABLE IF NOT EXISTS `location_share_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `recipient_id` bigint NOT NULL,
  `grant_id` char(36) NOT NULL,
  `version` bigint NOT NULL,
  `watermark` bigint NOT NULL,
  `kind` varchar(12) NOT NULL COMMENT 'UPDATED/INVALIDATED',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_location_share_event_recipient` (`recipient_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='recipient-side change feed';

-- Author-only topic cover pipeline. Bytes live in a private store outside the public /profile mapping;
-- rows here are the asset ledger and the per-topic selection (content slot) with its own version.
CREATE TABLE IF NOT EXISTS `topic_cover_asset` (
  `asset_id` char(36) NOT NULL,
  `source_version` char(36) NOT NULL,
  `owner_member_id` bigint NOT NULL,
  `content_hash` char(64) NOT NULL,
  `byte_size` int NOT NULL,
  `storage_key` varchar(255) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/UNAVAILABLE',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`asset_id`),
  KEY `idx_topic_cover_asset_owner` (`owner_member_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='author-only topic cover assets';

CREATE TABLE IF NOT EXISTS `topic_cover_selection` (
  `topic_id` bigint NOT NULL,
  `content_slot_id` bigint NOT NULL AUTO_INCREMENT,
  `selection_version` bigint NOT NULL DEFAULT 0,
  `selected_at_config_version` bigint DEFAULT NULL,
  `asset_id` char(36) DEFAULT NULL,
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`content_slot_id`),
  UNIQUE KEY `uk_topic_cover_selection_topic` (`topic_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='topic cover content slot and selection';

CREATE TABLE IF NOT EXISTS `topic_cover_request` (
  `member_id` bigint NOT NULL,
  `request_id` char(36) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `receipt_json` json NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`, `request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='topic cover selection receipts';

-- Durable request wrapper around the four original project writers (api/topic/{create,update,v2/create,v2/update}).
-- The receipt row commits in the same transaction as the original writer, so COMMITTED means the write happened.
CREATE TABLE IF NOT EXISTS `topic_edit_request` (
  `member_id` bigint NOT NULL,
  `request_id` char(36) NOT NULL,
  `original_path` varchar(32) NOT NULL,
  `payload_sha256` char(64) NOT NULL,
  `state` varchar(12) NOT NULL COMMENT 'COMMITTED/REJECTED',
  `original_result` json DEFAULT NULL,
  `message` varchar(255) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`, `request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='original project edit request receipts';

-- Versioned attribute-author mutations: one receipt per (member, requestId), committed with the mutation.
CREATE TABLE IF NOT EXISTS `topic_attribute_author_request` (
  `member_id` bigint NOT NULL,
  `request_id` char(36) NOT NULL,
  `topic_id` bigint NOT NULL,
  `expected_config_version` bigint NOT NULL,
  `method` varchar(8) NOT NULL,
  `payload_sha256` char(64) NOT NULL,
  `state` varchar(12) NOT NULL COMMENT 'COMMITTED',
  `response_json` json NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`member_id`, `request_id`),
  KEY `idx_attribute_author_topic` (`topic_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='attribute-author original request receipts';
