-- CANDIDATE ONLY. Do not execute.
-- W20 CITY board publication tables for a later reviewed migration.
-- This file creates no roles, inserts no sys_menu and no sys_role_menu.
-- Candidate permission keys stay default-off until a separate reviewed grant:
--   cityboard:draft:save
--   cityboard:draft:reopen
--   cityboard:draft:cancel
--   cityboard:draft:query
--   cityboard:review:validate
--   cityboard:review:reject
--   cityboard:publish:freeze
--   cityboard:operation:query
--   cityboard:player:read
-- player:read is a publication-matrix key, not a client role and not a sys_role grant.
-- No points wallet, no award ledger, no merchant or POI table is created or altered.

CREATE TABLE IF NOT EXISTS city_board_draft (
  id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  region_id VARCHAR(96) NOT NULL,
  version BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  content_json LONGTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  approval_hash CHAR(64) NULL,
  approver_user_id BIGINT NULL,
  approval_request_id VARCHAR(96) NULL,
  parent_release_id BIGINT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_city_board_draft_owner (owner_user_id, status),
  KEY idx_city_board_draft_region (region_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_board_release (
  id BIGINT NOT NULL,
  region_id VARCHAR(96) NOT NULL,
  draft_id BIGINT NOT NULL,
  revision BIGINT NOT NULL,
  rules_release_id VARCHAR(96) NOT NULL,
  game_id VARCHAR(96) NOT NULL,
  board_id VARCHAR(96) NOT NULL,
  season_id VARCHAR(96) NOT NULL,
  title VARCHAR(160) NOT NULL,
  lifecycle VARCHAR(16) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  approval_hash CHAR(64) NOT NULL,
  approver_user_id BIGINT NOT NULL,
  snapshot_json LONGTEXT NOT NULL,
  current_flag TINYINT NOT NULL,
  publisher_user_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_board_release_revision (region_id, revision),
  UNIQUE KEY uk_city_board_release_rules (rules_release_id),
  KEY idx_city_board_release_current (region_id, current_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_board_operation (
  id BIGINT NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  action VARCHAR(32) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  request_hash CHAR(64) NOT NULL,
  http_status INT NOT NULL,
  result_json LONGTEXT NOT NULL,
  draft_id BIGINT NULL,
  release_id BIGINT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_board_operation_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
