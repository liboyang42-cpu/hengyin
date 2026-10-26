-- Additive migration; legacy reached bits are NOT proof of subscription delivery.
ALTER TABLE official_broadcast ADD COLUMN audience_frozen TINYINT NOT NULL DEFAULT 0;
ALTER TABLE official_broadcast_log
 ADD COLUMN delivery_state VARCHAR(32) NULL,
 ADD COLUMN frozen_title VARCHAR(128) NULL,
 ADD COLUMN frozen_sub TEXT NULL,
 ADD COLUMN intent_id BIGINT NULL,
 ADD COLUMN message_id BIGINT NULL,
 ADD COLUMN attempts INT NOT NULL DEFAULT 0,
 ADD COLUMN update_time DATETIME(3) NULL;
ALTER TABLE official_broadcast
 ADD COLUMN client_request_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN request_owner VARCHAR(64) NULL,
 ADD COLUMN request_hash VARCHAR(64) NULL,
 ADD UNIQUE KEY uk_broadcast_request(request_owner,client_request_id);
CREATE TABLE IF NOT EXISTS official_broadcast_creation (
 request_owner VARCHAR(64) NOT NULL, request_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL, broadcast_id BIGINT NULL,
 PRIMARY KEY(request_owner,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
