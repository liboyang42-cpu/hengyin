-- Inactive W02 review submission binding only. Not a full release or run migration.
-- Apply only after review and isolated rehearsal; no production execution is authorized.
-- Requires existing content_capture_snapshot and audit_task. Preserves all old review envelopes.
CREATE TABLE content_capture_review_binding (
 id BIGINT NOT NULL AUTO_INCREMENT,
 snapshot_id BIGINT NOT NULL,
 topic_id BIGINT NOT NULL,
 member_id BIGINT NOT NULL,
 request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 audit_task_id BIGINT NOT NULL,
 source_config_version BIGINT NOT NULL,
 content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id),
 UNIQUE KEY uk_capture_review_request(topic_id,member_id,request_id),
 UNIQUE KEY uk_capture_review_snapshot(snapshot_id),
 UNIQUE KEY uk_capture_review_task(audit_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable candidate review identity, not release authority';
DELIMITER $$
CREATE TRIGGER content_capture_review_no_update BEFORE UPDATE ON content_capture_review_binding
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Candidate review bindings cannot be updated'$$
CREATE TRIGGER content_capture_review_no_delete BEFORE DELETE ON content_capture_review_binding
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Candidate review bindings cannot be deleted'$$
DELIMITER ;
