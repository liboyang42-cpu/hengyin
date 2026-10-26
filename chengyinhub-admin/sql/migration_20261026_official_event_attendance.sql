-- Additive only. Apply in an approved test environment before enabling consumers.
CREATE TABLE IF NOT EXISTS official_event_attendance_config (
 event_id BIGINT NOT NULL PRIMARY KEY, config_json TEXT NOT NULL,
 version BIGINT NOT NULL DEFAULT 1, updated_by BIGINT NOT NULL,
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS official_event_attendance (
 participation_id BIGINT NOT NULL PRIMARY KEY, event_id BIGINT NOT NULL,
 member_id BIGINT NOT NULL, credential VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version BIGINT NOT NULL DEFAULT 1, checked_at DATETIME(3) NULL, checked_by BIGINT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_event_holder(event_id, member_id), UNIQUE KEY uk_credential(credential)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
