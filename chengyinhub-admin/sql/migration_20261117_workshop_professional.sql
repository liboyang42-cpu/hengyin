-- W18 professional materialization of a purchased workshop text (app Core/WorkshopPaidProfessionalService.swift).
-- Category: (2) new tables only, no existing row is written. Each submission is a review item on the same state machine
-- as external_game_submission (PENDING / ESCALATED / APPROVED / REJECTED / CANCELLED), and every transition appends one
-- workshop_professional_event row (actor AUTHOR / ADMIN / SYSTEM). No money column. Rerunnable (IF NOT EXISTS).
CREATE TABLE IF NOT EXISTS workshop_professional_operation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'app requestId (lowercase UUID)',
  operation_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'opaque history cursor key',
  owner_member_id BIGINT NOT NULL,
  license_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'w18-paid-{workshop_order.id}',
  command_json TEXT NOT NULL COMMENT 'the canonical command (no content, terms or credentials)',
  command_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  review_status VARCHAR(16) NOT NULL,
  rejection_code VARCHAR(80) NULL,
  target_topic_id BIGINT NOT NULL,
  owned_draft_id BIGINT NOT NULL,
  confirmed_mode VARCHAR(24) NOT NULL,
  purchased_version_id VARCHAR(128) NOT NULL,
  template_id BIGINT NULL COMMENT 'the guarded private cms_member_template created on APPROVED',
  template_content_hash CHAR(64) NULL,
  missing_inputs_json VARCHAR(255) NULL,
  committed_time DATETIME(3) NULL,
  row_version INT NOT NULL DEFAULT 0,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_workshop_professional_request (request_id),
  UNIQUE KEY uk_workshop_professional_key (operation_key),
  KEY idx_workshop_professional_history (owner_member_id, license_id, operation_key),
  KEY idx_workshop_professional_queue (review_status, create_time),
  CONSTRAINT ck_workshop_professional_status CHECK (review_status IN ('PENDING','ESCALATED','APPROVED','REJECTED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='W18 professional materialization operations';

CREATE TABLE IF NOT EXISTS workshop_professional_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  operation_id BIGINT NOT NULL,
  from_status VARCHAR(16) NULL,
  to_status VARCHAR(16) NOT NULL,
  actor_type VARCHAR(8) NOT NULL COMMENT 'AUTHOR | ADMIN | SYSTEM',
  actor_id BIGINT NULL,
  reason VARCHAR(500) NULL,
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_workshop_professional_event (operation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='W18 professional review transitions (append-only)';
