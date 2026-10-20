-- W05 authoring evidence only. No cash offer, inventory copy, wallet or publication activation.
-- Apply only through the normal reviewed migration process; never run against production from this task.
CREATE TABLE merchant_authoring_candidate (
  id BIGINT NOT NULL AUTO_INCREMENT,
  principal_member_id BIGINT NOT NULL,
  merchant_row_id BIGINT NOT NULL,
  owner_member_id BIGINT NOT NULL,
  facts_json LONGTEXT NOT NULL,
  facts_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  constraints_json LONGTEXT NOT NULL,
  constraints_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  ai_trace_id VARCHAR(200) NOT NULL,
  template_json LONGTEXT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id), KEY idx_authoring_candidate_owner (owner_member_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE merchant_authoring_source (
  id BIGINT NOT NULL AUTO_INCREMENT,
  candidate_id BIGINT NOT NULL,
  owner_member_id BIGINT NOT NULL,
  merchant_row_id BIGINT NOT NULL,
  actual_template_id BIGINT NOT NULL,
  template_snapshot_json LONGTEXT NOT NULL,
  template_content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  confirmation_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  source_json LONGTEXT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_authoring_source_candidate (candidate_id),
  UNIQUE KEY uk_authoring_source_template (actual_template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
