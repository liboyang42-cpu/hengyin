-- Source only; not executed. Original actor identity remains separate from resolved merchant owner.
CREATE TABLE IF NOT EXISTS template_adoption_receipt (
 actor_member_id BIGINT NOT NULL,
 request_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 owner_member_id BIGINT NOT NULL,
 scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_id BIGINT NOT NULL,
 source_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 draft_id BIGINT NULL,
 draft_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 reason VARCHAR(64) NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(actor_member_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
