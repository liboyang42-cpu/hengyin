-- W20 bounded city visit persistence. Additive, no seed approvals, event allocations, prices, balances, or live wiring.
-- Requires existing ums_member and ums_member_points_detail plus reward-business and event unique constraints.
-- Real MySQL migration/transaction verification and a reviewed dedicated CITY event allocation remain release gates.
-- ASCII binary keys preserve exact case; no soft-delete/rejoin path removes a business key.
CREATE TABLE city_board (
  board_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload LONGTEXT NOT NULL,
  state_version BIGINT NOT NULL,
  PRIMARY KEY (board_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE city_action_round (
  board_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  round_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  team_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  round_sequence BIGINT NOT NULL,
  payload LONGTEXT NOT NULL,
  PRIMARY KEY (board_id, round_key),
  UNIQUE KEY uk_city_round_business (board_id, team_id, round_sequence),
  CONSTRAINT fk_city_round_board FOREIGN KEY (board_id) REFERENCES city_board(board_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE city_service_visit (
  board_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  visit_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  team_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  service_instance_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  visit_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload LONGTEXT NOT NULL,
  PRIMARY KEY (board_id, visit_key),
  UNIQUE KEY uk_city_visit_business (board_id, team_id, service_instance_id, visit_scope),
  CONSTRAINT fk_city_visit_board FOREIGN KEY (board_id) REFERENCES city_board(board_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE city_action_effect (
  id BIGINT NOT NULL AUTO_INCREMENT,
  board_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  effect_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sequence BIGINT NOT NULL,
  payload LONGTEXT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_effect_business (board_id, effect_key),
  UNIQUE KEY uk_city_effect_sequence (board_id, sequence),
  CONSTRAINT fk_city_effect_board FOREIGN KEY (board_id) REFERENCES city_board(board_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE city_action_receipt (
  board_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id BIGINT NOT NULL,
  request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  effect_id BIGINT NOT NULL,
  PRIMARY KEY (board_id, actor_id, request_id),
  UNIQUE KEY uk_city_request_hash (board_id, request_key),
  CONSTRAINT fk_city_request_effect FOREIGN KEY (effect_id) REFERENCES city_action_effect(id),
  CONSTRAINT fk_city_request_board FOREIGN KEY (board_id) REFERENCES city_board(board_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE city_consumed_scope (
  board_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scope_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  effect_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (board_id, kind, scope_key),
  CONSTRAINT fk_city_scope_effect FOREIGN KEY (board_id, effect_key) REFERENCES city_action_effect(board_id, effect_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE city_reward_budget (
  budget_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  board_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  rules_release_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  approval_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version BIGINT NOT NULL,
  ceiling BIGINT NOT NULL,
  reserved BIGINT NOT NULL,
  consumed BIGINT NOT NULL,
  released BIGINT NOT NULL,
  PRIMARY KEY (budget_id),
  CONSTRAINT fk_city_budget_board FOREIGN KEY (board_id) REFERENCES city_board(board_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE city_reward_reservation (
  reservation_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  board_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  budget_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  effect_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  offer_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  gross BIGINT NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (reservation_id),
  UNIQUE KEY uk_city_reservation_effect (board_id, effect_key),
  CONSTRAINT fk_city_reservation_board FOREIGN KEY (board_id) REFERENCES city_board(board_id),
  CONSTRAINT fk_city_reservation_budget FOREIGN KEY (budget_id) REFERENCES city_reward_budget(budget_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE city_point_effect_member (
  effect_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  reward_credit BIGINT NOT NULL,
  fee_credit BIGINT NOT NULL,
  fee_debit BIGINT NOT NULL,
  net_change BIGINT NOT NULL,
  before_points INT NOT NULL,
  after_points INT NOT NULL,
  points_detail_id BIGINT NULL,
  mapping_approval VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (effect_id, member_id),
  UNIQUE KEY uk_city_point_detail (points_detail_id),
  CONSTRAINT fk_city_point_effect FOREIGN KEY (effect_id) REFERENCES city_action_effect(id),
  CONSTRAINT fk_city_point_member FOREIGN KEY (member_id) REFERENCES ums_member(id),
  CONSTRAINT fk_city_point_detail FOREIGN KEY (points_detail_id) REFERENCES ums_member_points_detail(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE city_domain_outbox (
  effect_id BIGINT NOT NULL,
  board_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sequence BIGINT NOT NULL,
  event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload LONGTEXT NOT NULL,
  PRIMARY KEY (effect_id),
  UNIQUE KEY uk_city_outbox_sequence (board_id, sequence),
  CONSTRAINT fk_city_outbox_effect FOREIGN KEY (effect_id) REFERENCES city_action_effect(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Readback: runner must fail if any required physical invariant is absent.
-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND engine='InnoDB'
   AND table_name IN ('city_board','city_action_round','city_service_visit','city_action_effect','city_action_receipt',
   'city_consumed_scope','city_reward_budget','city_reward_reservation','city_point_effect_member','city_domain_outbox'))=10
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
       AND table_name='ums_member' AND column_name='point' AND data_type='int'
       AND column_type NOT LIKE '%unsigned%')=1
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
       WHERE table_schema=DATABASE() AND table_name='ums_member_points_detail'
       AND index_name='uk_member_event_once' AND non_unique=0)='member_id,event_type,event_id'
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
       WHERE table_schema=DATABASE() AND table_name='ums_member_points_detail'
       AND index_name='uk_member_reward_business' AND non_unique=0)='member_id,reward_business_key'
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
       AND table_name='city_board' AND column_name='board_id' AND collation_name='ascii_bin')=1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
       AND table_name='city_action_receipt' AND column_name='request_id' AND collation_name='ascii_bin')=1
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
       WHERE table_schema=DATABASE() AND table_name='city_service_visit'
       AND index_name='uk_city_visit_business' AND non_unique=0)='board_id,team_id,service_instance_id,visit_scope'
) AS migration_assertion;
