-- PROPOSAL ONLY. No runtime source, reviewer permission, publication grant, or legacy backfill.
-- Apply only through the approved migration workflow. Flags remain false; review route is unregistered.
CREATE TABLE merchant_npc_knowledge_revision (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL,
  owner_member_id BIGINT NOT NULL,
  version BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content_json TEXT NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  audit_task_id BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_npc_version (merchant_id,version),
  UNIQUE KEY uk_merchant_npc_request (merchant_id,owner_member_id,request_id),
  UNIQUE KEY uk_merchant_npc_audit (audit_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE merchant_npc_knowledge_head (
  merchant_id BIGINT NOT NULL,
  owner_member_id BIGINT NOT NULL,
  version BIGINT NOT NULL,
  revision_id BIGINT NOT NULL,
  PRIMARY KEY (merchant_id),
  UNIQUE KEY uk_merchant_npc_head_revision (revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
