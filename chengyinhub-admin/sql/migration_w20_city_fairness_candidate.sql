-- CANDIDATE ONLY. Do not execute.
-- W20.21 fairness readings and read-back. No seed rows, no approved thresholds, no rewards.
-- A producer must keep (source_ref, event_id) unique. The same pair with a different payload is a conflict.
-- event_id alone is not unique across sources.

CREATE TABLE IF NOT EXISTS city_fairness_parameter (
  id BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  source_ref VARCHAR(128) NOT NULL,
  delta_millis BIGINT NOT NULL,
  horizon_millis BIGINT NOT NULL,
  lower_effective_events INT NOT NULL,
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
  join_time BIGINT NULL,
  team_size INT NULL,
  action_cost INT NULL,
  newcomer VARCHAR(32) NULL,
  gain_amount BIGINT NULL,
  verification_millis BIGINT NULL,
  PRIMARY KEY (source_ref, event_id),
  KEY idx_city_fairness_event_actor_time (actor_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_fairness_read (
  account_id BIGINT NOT NULL,
  query_id VARCHAR(64) NOT NULL,
  as_of BIGINT NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  PRIMARY KEY (account_id, query_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
