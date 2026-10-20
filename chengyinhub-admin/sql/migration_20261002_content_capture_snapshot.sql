-- W02 inactive server-only immutable candidate storage. Apply only through reviewed migration flow.
-- NOT executed on any production database by this change. No old-topic conversion or runtime adoption.
-- Preflight: verify table/trigger names are absent; inspect MySQL version, InnoDB, charset, privileges,
-- routing/interceptors, packet limits and backup/restore. Existing names require manual reconciliation.
-- Contents include secret answers/story/NPC knowledge: never expose as a public manifest or log body.
-- LONGTEXT preserves canonical bytes; a native JSON column could reformat them.
-- Canonical input is bounded to 1,048,576 UTF-16 code units, potentially multiple MiB in UTF-8.
-- No release ID, approval, publication state or rights authority is allocated here.
CREATE TABLE content_capture_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    captured_by_member_id BIGINT NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_config_version BIGINT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonical_json LONGTEXT NOT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contract_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonicalization_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_capture_request (topic_id, captured_by_member_id, request_id),
    KEY idx_content_capture_topic_hash (topic_id, content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Server-only frozen incomplete candidates, not releases';

-- Fail closed for all frozen content and metadata. No normal product update/delete route exists.
-- Repository runner requires explicit custom delimiters for all triggers, including single statements.
-- These guards still require real MySQL rehearsal.
DELIMITER $$
CREATE TRIGGER content_capture_snapshot_no_update BEFORE UPDATE ON content_capture_snapshot
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Frozen content capture snapshots cannot be updated'$$
CREATE TRIGGER content_capture_snapshot_no_delete BEFORE DELETE ON content_capture_snapshot
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Frozen content capture snapshots cannot be deleted'$$
DELIMITER ;

-- Rollback is a reviewed operational decision only: after verifying absence of retained/audit references,
-- archive/export safely and remove this additive schema. Never delete snapshots to "undo" a publication.
-- Trigger behavior, binary request-key equality, InnoDB races and restart durability need MySQL tests.
