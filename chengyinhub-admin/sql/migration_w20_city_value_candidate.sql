-- CANDIDATE ONLY. Do not execute.
-- W20 CITY value-report tables for a later reviewed migration.
-- This file creates no roles, inserts no sys_menu and no sys_role_menu.
-- Candidate permission key stays default-off until a separate reviewed grant:
--   cityvalue:report:query
-- No points wallet, no award ledger, no payment table is created or altered.

CREATE TABLE IF NOT EXISTS city_value_event (
  id BIGINT NOT NULL,
  dedupe_key CHAR(64) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  source_id VARCHAR(96) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  region_id VARCHAR(96) NOT NULL,
  board_id VARCHAR(96) NOT NULL,
  season_id VARCHAR(96) NOT NULL,
  rules_release_id VARCHAR(96) NOT NULL,
  member_id BIGINT NOT NULL,
  visit_id VARCHAR(96) NULL,
  service_id VARCHAR(96) NULL,
  action_code VARCHAR(96) NULL,
  goal_id VARCHAR(96) NULL,
  reason_code VARCHAR(96) NULL,
  amount BIGINT NULL,
  unit VARCHAR(16) NULL,
  currency CHAR(3) NULL,
  rating INT NULL,
  detail_json VARCHAR(2000) NULL,
  occurred_at DATETIME(3) NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_value_event_dedupe (dedupe_key),
  KEY idx_city_value_event_scope (region_id, rules_release_id, occurred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_value_report (
  id BIGINT NOT NULL,
  result_key CHAR(64) NOT NULL,
  region_id VARCHAR(96) NOT NULL,
  rules_release_id VARCHAR(96) NOT NULL,
  definition_hash CHAR(64) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  body_json LONGTEXT NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_value_report_key (result_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_value_request (
  id BIGINT NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  result_id BIGINT NULL,
  region_id VARCHAR(96) NULL,
  rules_release_id VARCHAR(96) NULL,
  body_json LONGTEXT NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_value_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
