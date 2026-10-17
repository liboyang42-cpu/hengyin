-- New private goals only. No changes to completion, XP, route visibility or historical facts.
CREATE TABLE IF NOT EXISTS personal_exploration_goal (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  metric VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target INT NOT NULL,
  start_date CHAR(10) CHARACTER SET ascii NOT NULL,
  end_date_exclusive CHAR(10) CHARACTER SET ascii NOT NULL,
  created_at_ms BIGINT NOT NULL,
  cancelled TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uq_goal_owner_request (member_id, request_id),
  KEY idx_goal_owner_page (member_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Private exploration goals; aggregate completed route facts only';
