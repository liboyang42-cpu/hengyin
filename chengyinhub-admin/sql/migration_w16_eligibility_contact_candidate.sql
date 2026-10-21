-- REVIEW CANDIDATE ONLY. No production execution or activation is authorized.
-- MySQL/InnoDB. Apply only through the separately approved migration workflow.
-- No OPEN rows, no actor facts, no quota rows, no player data, and no venue defaults.
-- Absence of a current version, a complete rule, a fact, or a quota row stays closed.
-- This file does not read or copy ums_member, identity, address, or interest profile.

CREATE TABLE w16_eligibility_current (
  id INT NOT NULL PRIMARY KEY,
  version_id VARCHAR(128) COLLATE utf8mb4_bin NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_eligibility_rule (
  version_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  decision VARCHAR(16) NOT NULL,
  min_age INT NULL,
  max_age INT NULL,
  region_code VARCHAR(128) COLLATE utf8mb4_bin NULL,
  window_start BIGINT NULL,
  window_end BIGINT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_eligibility_fact (
  actor_id BIGINT NOT NULL PRIMARY KEY,
  rule_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  age_years INT NULL,
  region_code VARCHAR(128) COLLATE utf8mb4_bin NULL,
  reviewed_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_contact_quota (
  pool VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  scope VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  window_millis BIGINT NOT NULL,
  max_contacts INT NOT NULL,
  decision VARCHAR(16) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_contact_event (
  match_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  actor_id BIGINT NOT NULL,
  pool VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  scope VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  charged_at BIGINT NOT NULL,
  PRIMARY KEY (match_id, actor_id),
  KEY ix_w16_contact_actor (actor_id, pool, scope, charged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
