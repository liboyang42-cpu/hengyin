-- NEW proposed provenance sidecar. No migration is executed by this patch.
-- DEPENDENCY: independently reviewed legacy CMS dual-sentinel guards and marker-column migration.
-- Guard existence is by template_id with NO status/deletion filter. This table has no such flags.
-- Never delete a row on license refund/revocation. Resolve current rights through the source chain.
CREATE TABLE w18_cms_template_provenance (
  template_id BIGINT NOT NULL,
  schema_version VARCHAR(96) NOT NULL,
  owner_member_id BIGINT NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  command_hash VARCHAR(64) NOT NULL,
  binding_key VARCHAR(64) NOT NULL,
  source_kind VARCHAR(64) NOT NULL,
  owned_draft_id BIGINT NOT NULL,
  owned_draft_revision BIGINT NOT NULL,
  installation_request_id VARCHAR(64) NOT NULL,
  license_id VARCHAR(64) NOT NULL,
  module_id VARCHAR(128) NOT NULL,
  purchased_version_id VARCHAR(128) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  component_hash VARCHAR(64) NOT NULL,
  terms_hash VARCHAR(64) NOT NULL,
  original_target_draft_id BIGINT NOT NULL,
  original_target_revision BIGINT NOT NULL,
  original_target_payload_hash VARCHAR(64) NOT NULL,
  confirmed_draft_revision BIGINT NOT NULL,
  confirmed_draft_payload_hash VARCHAR(64) NOT NULL,
  target_topic_id BIGINT NOT NULL,
  confirmed_mode VARCHAR(64) NOT NULL,
  content_role_id BIGINT NOT NULL,
  route_config_version BIGINT NULL,
  owner_mode_fingerprint VARCHAR(64) NOT NULL,
  template_content_hash VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (template_id),
  UNIQUE KEY uq_w18_cms_request (request_key),
  UNIQUE KEY uq_w18_cms_consumed_theme (binding_key),
  UNIQUE KEY uq_w18_cms_owned_copy (owned_draft_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
