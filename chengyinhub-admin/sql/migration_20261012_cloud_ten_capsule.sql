-- Additive only. UTC epoch milliseconds are independent of connection/session timezone.
CREATE TABLE IF NOT EXISTS member_time_capsule (
 id BIGINT NOT NULL AUTO_INCREMENT,
 member_id BIGINT NOT NULL,
 request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 body TEXT NULL,
 photo_key VARCHAR(512) NULL,
 unlock_at_ms BIGINT NOT NULL,
 created_at_ms BIGINT NOT NULL,
 deleted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_capsule_member_request(member_id,request_id),
 KEY idx_capsule_member_page(member_id,deleted,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
