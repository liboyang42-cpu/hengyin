-- Existing merchant coupon commands only. No activation or production execution implied.
-- New table; legacy requests never access it. Keep coupon.command-receipts.enabled=false.
-- No TTL/cascade: deleting receipts would permit old command keys to execute again.
CREATE TABLE IF NOT EXISTS coupon_command_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_member_id BIGINT NOT NULL,
    scope_type TINYINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    owner_member_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_version SMALLINT NOT NULL,
    payload_sha256 BINARY(32) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    reason_code VARCHAR(48) NOT NULL,
    result_code INT NOT NULL,
    result_message VARCHAR(512) NOT NULL,
    coupon_id BIGINT NULL,
    coupon_status_at_execution INT NULL,
    completed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_command (actor_member_id, scope_type, merchant_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
