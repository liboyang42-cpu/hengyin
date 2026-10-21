-- REVIEW CANDIDATE ONLY. No production execution or activation is authorized.
-- MySQL/InnoDB. Apply only through the separately approved migration workflow.
-- Index rows commit in the same database transaction as the real team, member, and IM group.
-- This script does not create a team and does not alter play_team.
CREATE TABLE w16_play_team_handoff (
  namespace VARCHAR(64) NOT NULL,
  request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  match_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  leader_member_id BIGINT NOT NULL,
  peer_member_id BIGINT NOT NULL,
  activity_id BIGINT NOT NULL,
  team_id BIGINT NOT NULL,
  phase VARCHAR(32) NOT NULL,
  PRIMARY KEY (namespace, request_id),
  UNIQUE KEY uk_w16_play_team_handoff_match (namespace, match_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
