-- Coordinated theme-team generations. New migration only; never modifies historical migration bodies.
-- This file is source only. Target schema/ledger preflight and DDL execution need separate authorization.
CREATE TABLE theme_team_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  team_id BIGINT NOT NULL,
  activity_id BIGINT NOT NULL,
  topic_id BIGINT NOT NULL,
  previous_run_id BIGINT NULL,
  source_session_id BIGINT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_digest VARCHAR(64) NOT NULL,
  play_instance_id VARCHAR(64) NULL,
  generation INT NOT NULL,
  config_version BIGINT NOT NULL,
  config_snapshot_json MEDIUMTEXT NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_theme_team_request (team_id, activity_id, topic_id, request_key),
  UNIQUE KEY uk_theme_team_source (team_id, activity_id, topic_id, source_session_id),
  UNIQUE KEY uk_theme_team_generation (team_id, activity_id, topic_id, generation),
  UNIQUE KEY uk_theme_team_child (previous_run_id),
  UNIQUE KEY uk_theme_team_instance (play_instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable coordinated generation; token assigned before transaction commits';

CREATE TABLE theme_team_run_member (
  run_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  previous_session_id BIGINT NULL,
  session_id BIGINT NOT NULL,
  PRIMARY KEY (run_id, member_id),
  UNIQUE KEY uk_theme_team_member_session (session_id),
  UNIQUE KEY uk_theme_team_member_previous (run_id, previous_session_id),
  KEY ix_theme_team_member (member_id, run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Replay audit only, not a live-team admission whitelist';
