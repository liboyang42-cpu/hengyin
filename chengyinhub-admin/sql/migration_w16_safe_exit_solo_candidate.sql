-- REVIEW CANDIDATE ONLY. No production execution or activation is authorized.
-- MySQL/InnoDB. Apply only through the separately approved migration workflow.
-- Exit ledger only. No team rows, rewards, penalties, eligibility grants, or solo-goal seed data.
CREATE TABLE w16_safe_exit_guard (
  id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO w16_safe_exit_guard(id) VALUES (1);

CREATE TABLE w16_safe_exit (
  ticket_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  match_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
  choice VARCHAR(16) NOT NULL,
  phase VARCHAR(16) NOT NULL,
  handoff VARCHAR(32) NOT NULL,
  solo_state VARCHAR(24) NOT NULL,
  solo_block VARCHAR(64) NULL,
  goal_ref VARCHAR(128) COLLATE utf8mb4_bin NULL,
  launch_receipt VARCHAR(128) COLLATE utf8mb4_bin NULL,
  resumed TINYINT NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE KEY uk_w16_safe_exit_receipt (launch_receipt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
