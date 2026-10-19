-- NEW staged owner-confirmation ledger. No checkout, payment, grant issuance or activation.
-- Separate migration review required. Never apply this file to production in this task.
-- Immutable rows preserve command equality and the original technical hold deadline across retries.
CREATE TABLE w18_paid_install_command (
    command_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_member_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    core_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    license_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    module_id VARCHAR(128) NOT NULL,
    version_id VARCHAR(128) NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    terms_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_draft_id BIGINT NOT NULL,
    target_revision BIGINT NOT NULL,
    business_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    planning_region VARCHAR(128) NOT NULL,
    commercial_use BOOLEAN NOT NULL,
    offer_version VARCHAR(128) NOT NULL,
    requested_until DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY(command_key),
    KEY idx_w18_paid_command_owner_license(buyer_member_id,license_id,command_key),
    UNIQUE KEY uq_w18_paid_command_owner_request(buyer_member_id,request_id),
    UNIQUE KEY uq_w18_paid_command_core_request(buyer_member_id,core_request_id),
    CONSTRAINT fk_w18_paid_command_buyer FOREIGN KEY(buyer_member_id) REFERENCES ums_member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
