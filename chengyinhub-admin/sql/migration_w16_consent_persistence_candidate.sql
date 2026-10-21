-- REVIEW CANDIDATE ONLY. No production execution or activation is authorized.
-- MySQL/InnoDB. Apply only through the separately approved migration workflow.
-- Additive schema; contains no player data, eligibility grants, endpoints or venue defaults.
CREATE TABLE w16_consent_guard (
  id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO w16_consent_guard(id) VALUES (1);

CREATE TABLE w16_match_ticket (
  id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  pool VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  eligibility_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  expires_at BIGINT NOT NULL,
  state VARCHAR(24) NOT NULL,
  match_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
  active_actor_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN state IN ('WAITING','RESERVED','ACCEPTED') THEN actor_id ELSE NULL END) STORED,
  UNIQUE KEY uk_w16_active_actor(active_actor_id),
  KEY ix_w16_ticket_expiry(state, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_rendezvous_match (
  id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  first_ticket VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  second_ticket VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  public_place VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  expires_at BIGINT NOT NULL,
  version BIGINT NOT NULL,
  first_accepted_at BIGINT NULL,
  second_accepted_at BIGINT NULL,
  state VARCHAR(24) NOT NULL,
  KEY ix_w16_match_expiry(state, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per active ticket, independent of which side of a match it occupies.
CREATE TABLE w16_match_reservation (
  ticket_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  match_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  KEY ix_w16_reservation_match(match_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
