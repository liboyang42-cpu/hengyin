-- Source-only additive migration. Production execution belongs to the owner.
-- No title name, tax number, financial amount or authentication material is stored.
-- Do not backfill current deleted/cancelled state as historical command proof.
CREATE TABLE user_invoice_command_receipt (
    member_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id BIGINT NOT NULL,
    outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    create_time DATETIME NOT NULL,
    completed_at DATETIME NULL,
    PRIMARY KEY (member_id, request_id),
    CONSTRAINT ck_invoice_command_operation CHECK (operation IN ('DELETE_TITLE', 'CANCEL_APPLICATION')),
    CONSTRAINT ck_invoice_command_target CHECK (target_id > 0),
    CONSTRAINT ck_invoice_command_outcome CHECK (
        (outcome IS NULL AND completed_at IS NULL) OR
        (outcome IS NOT NULL AND completed_at IS NOT NULL AND
            ((operation = 'DELETE_TITLE' AND outcome = 'DELETED') OR
             (operation = 'CANCEL_APPLICATION' AND outcome = 'CANCELLED'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
