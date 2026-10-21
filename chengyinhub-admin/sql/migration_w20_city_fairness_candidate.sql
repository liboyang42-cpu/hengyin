-- CANDIDATE ONLY. Do not execute.
-- W20.21 fairness readings. No seed rows, no approved thresholds, no rewards.

CREATE TABLE IF NOT EXISTS city_fairness_parameter (
  id BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  source_ref VARCHAR(128) NOT NULL,
  delta_millis BIGINT NOT NULL,
  horizon_millis BIGINT NOT NULL,
  lower_effective_events INT NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_fairness_event (
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
  PRIMARY KEY (event_id),
  KEY idx_city_fairness_event_time (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
