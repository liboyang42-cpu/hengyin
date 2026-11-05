-- Chain persistence foundation. MySQL 8, application times stored as UTC DATETIME(3).
-- Source preparation only; no controller, permission resolver or message sender is enabled.
-- State names are internal storage values from ChainParticipationTransition, not wire codes.
CREATE TABLE IF NOT EXISTS im_chain (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    creator_member_id BIGINT NOT NULL,
    title TEXT NOT NULL,
    max_slots INT NOT NULL,
    active_count INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    deadline_at DATETIME(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_chain_message (message_id),
    UNIQUE KEY uk_chain_conversation (id, conversation_id),
    CONSTRAINT chk_chain_scope CHECK (conversation_id > 0 AND message_id > 0 AND creator_member_id > 0),
    CONSTRAINT chk_chain_capacity CHECK (max_slots > 0 AND active_count >= 0 AND active_count <= max_slots),
    CONSTRAINT chk_chain_state CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT chk_chain_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS im_chain_entry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    chain_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    content LONGTEXT NOT NULL,
    position INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_chain_member (chain_id, member_id),
    UNIQUE KEY uk_chain_entry_request (chain_id, request_id),
    CONSTRAINT fk_chain_entry_chain FOREIGN KEY (chain_id) REFERENCES im_chain(id),
    CONSTRAINT chk_chain_entry_scope CHECK (member_id > 0 AND position > 0),
    CONSTRAINT chk_chain_entry_state CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT chk_chain_entry_version CHECK (version >= 0),
    CONSTRAINT chk_chain_entry_request CHECK (CHAR_LENGTH(request_id) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Separate immutable command history: entry.request_id is only the latest relation command.
-- Binary NO PAD collation preserves case and trailing-space identity exactly.
CREATE TABLE IF NOT EXISTS im_chain_command_receipt (
    receipt_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    chain_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    action VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    expected_chain_version BIGINT NOT NULL,
    expected_entry_version BIGINT NOT NULL,
    expected_active_count INT NOT NULL,
    next_active_count INT NOT NULL,
    effect VARCHAR(32) NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (receipt_id),
    UNIQUE KEY uk_chain_command (chain_id, request_id),
    UNIQUE KEY uk_chain_receipt (chain_id, receipt_id),
    CONSTRAINT fk_chain_receipt_scope FOREIGN KEY (chain_id, conversation_id) REFERENCES im_chain(id, conversation_id),
    CONSTRAINT chk_chain_receipt_identity CHECK (member_id > 0 AND CHAR_LENGTH(receipt_id) > 0 AND CHAR_LENGTH(request_id) > 0),
    CONSTRAINT chk_chain_receipt_versions CHECK (expected_chain_version >= 0 AND expected_entry_version >= 0),
    CONSTRAINT chk_chain_receipt_counts CHECK (expected_active_count >= 0 AND next_active_count >= 0),
    CONSTRAINT chk_chain_receipt_effect CHECK (
        (effect='NO_CHANGE' AND action IN ('JOIN','WITHDRAW') AND next_active_count=expected_active_count)
        OR (effect IN ('INSERT_RELATION','REACTIVATE_RELATION') AND action='JOIN' AND next_active_count=expected_active_count+1)
        OR (effect='WITHDRAW_RELATION' AND action='WITHDRAW' AND next_active_count=expected_active_count-1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS im_chain_entry_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    chain_id BIGINT NOT NULL,
    receipt_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    effect VARCHAR(32) NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_chain_event_receipt (receipt_id),
    CONSTRAINT fk_chain_event_receipt FOREIGN KEY (chain_id, receipt_id) REFERENCES im_chain_command_receipt(chain_id, receipt_id),
    CONSTRAINT chk_chain_event_effect CHECK (effect IN ('NO_CHANGE','INSERT_RELATION','REACTIVATE_RELATION','WITHDRAW_RELATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
