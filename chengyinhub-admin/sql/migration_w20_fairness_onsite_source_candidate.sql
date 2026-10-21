-- CANDIDATE ONLY. Do not execute. No seed rows.
-- Outing and the onsite counting switch belong to fairness. The onsite task tables stay with their owner.
-- rules_version in the policy row is rules_release_id. It is not release_id and not content_hash.
-- Absence of an APPROVED counts_as_effective_outing = 1 row leaves the source off.

CREATE TABLE IF NOT EXISTS city_fairness_outing (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  instance_id VARCHAR(36) NOT NULL,
  board_id VARCHAR(96) NOT NULL,
  region_id VARCHAR(96) NOT NULL,
  season_id VARCHAR(96) NOT NULL,
  rules_release_id VARCHAR(96) NOT NULL,
  release_id BIGINT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_fairness_outing_instance (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_fairness_source_policy (
  id BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  source_ref VARCHAR(128) NOT NULL,
  board_id VARCHAR(96) NOT NULL,
  region_id VARCHAR(96) NOT NULL,
  season_id VARCHAR(96) NOT NULL,
  rules_version VARCHAR(96) NOT NULL,
  counts_as_effective_outing TINYINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_fairness_source_policy (source_ref, board_id, region_id, season_id, rules_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_fairness_source_receipt (
  source_ref VARCHAR(128) NOT NULL,
  event_id VARCHAR(96) NOT NULL,
  received_at BIGINT NOT NULL,
  PRIMARY KEY (source_ref, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
