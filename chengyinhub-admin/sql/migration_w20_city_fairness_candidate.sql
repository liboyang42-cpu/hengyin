-- CANDIDATE ONLY. Do not execute.
-- W20.21 fairness readings and read-back. No seed rows, no approved thresholds, no rewards.
-- A producer must keep (source_ref, event_id) unique. The same pair with a different payload is a conflict.
-- event_id alone is not unique across sources.
-- L is grace_millis after the horizon. A member is mature when t0 + horizon_millis + grace_millis <= as_of.
-- merge_window_millis and state_rule are required approved config. known_at is when the server learned the fact.
-- A read is one board_id, region_id, season_id and rules_version. Those four never share a snapshot.

CREATE TABLE IF NOT EXISTS city_fairness_parameter (
  id BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  source_ref VARCHAR(128) NOT NULL,
  board_id VARCHAR(64) NOT NULL,
  region_id VARCHAR(64) NOT NULL,
  season_id VARCHAR(64) NOT NULL,
  rules_version VARCHAR(64) NOT NULL,
  metric_version VARCHAR(64) NULL,
  delta_millis BIGINT NULL,
  horizon_millis BIGINT NULL,
  grace_millis BIGINT NULL,
  merge_window_millis BIGINT NULL,
  state_rule VARCHAR(64) NULL,
  max_events INT NULL,
  page_size INT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_fairness_event (
  source_ref VARCHAR(128) NOT NULL,
  event_id VARCHAR(96) NOT NULL,
  outing_id VARCHAR(96) NULL,
  kind VARCHAR(64) NOT NULL,
  actor_id BIGINT NOT NULL,
  occurred_at BIGINT NOT NULL,
  known_at BIGINT NOT NULL,
  subject_source VARCHAR(128) NULL,
  subject_event_id VARCHAR(96) NULL,
  join_time BIGINT NULL,
  team_size INT NULL,
  action_cost INT NULL,
  newcomer VARCHAR(32) NULL,
  gain_amount BIGINT NULL,
  verification_millis BIGINT NULL,
  board_id VARCHAR(64) NOT NULL,
  region_id VARCHAR(64) NOT NULL,
  season_id VARCHAR(64) NOT NULL,
  rules_version VARCHAR(64) NOT NULL,
  PRIMARY KEY (source_ref, event_id),
  KEY idx_city_fairness_event_scope_known (actor_id, board_id, region_id, season_id, rules_version, known_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_fairness_read (
  account_id BIGINT NOT NULL,
  query_id VARCHAR(64) NOT NULL,
  board_id VARCHAR(64) NOT NULL,
  region_id VARCHAR(64) NOT NULL,
  season_id VARCHAR(64) NOT NULL,
  rules_version VARCHAR(64) NOT NULL,
  as_of BIGINT NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  PRIMARY KEY (account_id, query_id, board_id, region_id, season_id, rules_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
