-- REVIEW CANDIDATE ONLY. No production execution or activation is authorized.
-- MySQL/InnoDB. Apply only through the separately approved migration workflow.
-- No OPEN policy rows. Absence is closed. No player data, grants, or venue defaults.
CREATE TABLE w16_policy_version (
  dimension VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  version_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  decision VARCHAR(16) NOT NULL,
  max_contacts INT NULL,
  ticket_ttl_millis BIGINT NULL,
  public_place VARCHAR(128) COLLATE utf8mb4_bin NULL,
  PRIMARY KEY (dimension, version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_contact_counter (
  actor_id BIGINT NOT NULL PRIMARY KEY,
  contacts BIGINT NOT NULL
) ENGINE=InnoDB;

CREATE TABLE w16_rendezvous_meeting (
  match_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  challenge_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  activity_id BIGINT NOT NULL,
  issued_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  first_confirmed_at BIGINT NULL,
  second_confirmed_at BIGINT NULL,
  team_id BIGINT NULL,
  state VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_w16_meeting_challenge (challenge_id),
  UNIQUE KEY uk_w16_meeting_idem (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_role_secret (
  ticket_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  match_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  role_clue VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  UNIQUE KEY uk_w16_role_match_actor (match_id, actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_safety_report (
  reporter_id BIGINT NOT NULL,
  reported_id BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (reporter_id, reported_id)
) ENGINE=InnoDB;
