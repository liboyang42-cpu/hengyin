-- Bytes stay in private OSS objects. An audit decision is the publication fact.
CREATE TABLE IF NOT EXISTS app_media_asset (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 member_id BIGINT NOT NULL,
 object_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 media_type VARCHAR(16) CHARACTER SET ascii NOT NULL,
 review_state VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'HOLD',
 review_reason VARCHAR(255) NULL,
 version INT NOT NULL DEFAULT 0,
 audit_task_id BIGINT NULL,
 create_time DATETIME NOT NULL,
 update_time DATETIME NOT NULL,
 UNIQUE KEY uk_media_object(object_key),
 KEY idx_media_owner(member_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_media_asset'
 AND column_name='review_state' AND is_nullable='NO' AND column_default='HOLD';
