-- CANDIDATE ONLY. Do not execute.
-- W20.19 city game operations diagnosis and correction.
-- This file grants nothing: no sys_menu and no sys_role_menu.
-- Direct edits are not a customer-service flow. Use the operations commands.
-- No points, wallet, fee, or other team's asset column is created.
-- city_game_ops_audit is append-only: the mapper has no update and no delete for it.

CREATE TABLE IF NOT EXISTS city_game_ops_case (
  id BIGINT NOT NULL,
  action_id VARCHAR(96) NOT NULL,
  region_id VARCHAR(96) NOT NULL,
  version BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  correction_state VARCHAR(32) NULL,
  execution VARCHAR(32) NULL,
  binding_status VARCHAR(32) NOT NULL,
  source_request_id VARCHAR(96) NOT NULL,
  owner_user_id BIGINT NULL,
  object_role VARCHAR(96) NULL,
  proposer_user_id BIGINT NULL,
  reviewer_user_id BIGINT NULL,
  reason VARCHAR(500) NULL,
  kind VARCHAR(32) NULL,
  merchant_id VARCHAR(96) NULL,
  object_type VARCHAR(96) NULL,
  object_id VARCHAR(96) NULL,
  trace_json LONGTEXT NOT NULL,
  proposal_json LONGTEXT NULL,
  rollback_json LONGTEXT NULL,
  classification VARCHAR(120) NULL,
  ops_note VARCHAR(500) NULL,
  blocker VARCHAR(64) NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_game_ops_case_action (action_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_game_ops_request (
  id BIGINT NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  body_json LONGTEXT NOT NULL,
  case_id BIGINT NULL,
  action VARCHAR(32) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  role_keys VARCHAR(255) NULL,
  state VARCHAR(32) NOT NULL,
  http_status INT NOT NULL,
  result_json LONGTEXT NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_game_ops_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_game_ops_audit (
  id BIGINT NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  case_id BIGINT NULL,
  action VARCHAR(32) NOT NULL,
  event_type VARCHAR(16) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  role_keys VARCHAR(255) NULL,
  request_hash CHAR(64) NOT NULL,
  http_status INT NOT NULL,
  result_json LONGTEXT NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_city_game_ops_audit_request (request_id, id),
  KEY idx_city_game_ops_audit_case (case_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_game_ops_store_fact (
  merchant_id VARCHAR(96) NOT NULL,
  fact_status VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL,
  case_id BIGINT NOT NULL,
  action_id VARCHAR(96) NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Candidate permission keys stay default-off until a separate reviewed grant:
--   citygame:ops:trace
--   citygame:ops:propose
--   citygame:ops:review
--   citygame:ops:apply
--   citygame:ops:query
