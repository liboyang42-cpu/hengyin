-- Apple facts/inbox only; no production configuration, SKU seed, license or billing activation.
CREATE TABLE IF NOT EXISTS workshop_apple_original (
    original_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    order_reference VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revoked TINYINT NOT NULL DEFAULT 0,
    revoked_at BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(original_id),
    UNIQUE KEY uk_apple_order(order_reference)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workshop_apple_transaction (
    transaction_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fact_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(transaction_id),
    KEY ix_apple_transaction_original(original_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workshop_apple_notification (
    notification_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    signed_payload MEDIUMTEXT NOT NULL,
    state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(96) NULL,
    attempts INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(notification_uuid),
    KEY ix_apple_notification_retry(state,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
