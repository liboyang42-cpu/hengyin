-- REVIEW CANDIDATE ONLY. No production execution or activation is authorized.
-- MySQL/InnoDB. Apply only through the separately approved migration workflow.
-- No team rows, no OPEN evidence, no player grants. Absence of a fact row is closed.
CREATE TABLE w16_handoff_request (
  request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  match_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  first_actor BIGINT NOT NULL,
  second_actor BIGINT NOT NULL,
  activity_id BIGINT NOT NULL,
  phase VARCHAR(32) NOT NULL,
  team_id BIGINT NULL,
  blocker VARCHAR(64) NULL,
  dispatched TINYINT NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  UNIQUE KEY uk_w16_handoff_match (match_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_meeting_evidence (
  match_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  place_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  first_actor BIGINT NOT NULL,
  second_actor BIGINT NOT NULL,
  risk_clear INT NOT NULL,
  time_distance_plausible INT NOT NULL,
  merchant_required INT NOT NULL,
  merchant_verified INT NOT NULL,
  review_required INT NOT NULL,
  review_approved INT NOT NULL,
  fact_source VARCHAR(64) NOT NULL,
  rule_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
