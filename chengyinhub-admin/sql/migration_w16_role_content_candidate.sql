-- REVIEW CANDIDATE ONLY. No production execution or activation is authorized.
-- MySQL/InnoDB. Apply only through the separately approved migration workflow.
-- No seed rows, no story text, no OPEN grant. Empty tables are unavailable.
-- secret_ref is an opaque correlation id. It is not role text and is not a clue.
CREATE TABLE w16_role_content_publication (
  goal_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  publish_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  state VARCHAR(16) NOT NULL,
  PRIMARY KEY (goal_id, publish_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_role_content_body (
  goal_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  publish_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  role_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  body VARCHAR(1024) COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (goal_id, publish_version, role_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_role_content_binding (
  match_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  goal_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  publish_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  first_role_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  second_role_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  state VARCHAR(16) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE w16_role_content_grant (
  ticket_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  match_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  goal_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  publish_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  role_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  secret_ref VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  UNIQUE KEY uk_w16_role_grant_match_actor (match_id, actor_id),
  UNIQUE KEY uk_w16_role_grant_secret_ref (secret_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
