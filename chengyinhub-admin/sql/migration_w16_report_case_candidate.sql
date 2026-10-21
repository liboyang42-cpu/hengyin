-- REVIEW CANDIDATE ONLY. No production execution or activation is authorized.
-- MySQL/InnoDB. Apply only through the separately approved migration workflow.
-- W16 report-case receipt. Not an IM table, not a community report, and not audit_task.
-- No ban, threshold, TTL, deadline, or counterpart column. No player seed data.
CREATE TABLE w16_report_case (
  case_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  reporter_id BIGINT NOT NULL,
  request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  ticket_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  match_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  payload_hash VARCHAR(64) NOT NULL,
  evidence_kind VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL,
  review_connection VARCHAR(32) NOT NULL,
  review_ref VARCHAR(128) COLLATE utf8mb4_bin NULL,
  disposition_code VARCHAR(32) NULL,
  created_at BIGINT NOT NULL,
  UNIQUE KEY uk_w16_report_case_request (reporter_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
