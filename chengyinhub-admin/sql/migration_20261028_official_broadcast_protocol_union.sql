-- Candidate: compose the two retained broadcast protocols; do not execute without integrator approval.
-- Requires the exact 20261023_d_official_broadcast_delivery prefix, stopped publishers/workers,
-- and REPLACED_UNAPPLIED custody for 20261026_official_broadcast_delivery.
-- Never run on a master-only, partially applied, or already composed installation.
CREATE TEMPORARY TABLE tmp_broadcast_union_guard (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_broadcast_union_guard (id) VALUES (1);
INSERT INTO tmp_broadcast_union_guard (id) SELECT 1 WHERE
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log'
       AND ((column_name = 'delivery_state' AND data_type = 'varchar'
             AND character_maximum_length = 16 AND is_nullable = 'NO' AND BINARY column_default = BINARY 'UNKNOWN')
         OR (column_name IN ('delivery_title', 'delivery_sub') AND data_type = 'text' AND is_nullable = 'YES')
         OR (column_name = 'failure_code' AND data_type = 'varchar' AND character_maximum_length = 64 AND is_nullable = 'YES')
         OR (column_name = 'message_id' AND data_type = 'bigint' AND is_nullable = 'YES')
         OR (column_name = 'reached_time' AND data_type = 'datetime' AND is_nullable = 'YES'))) <> 6
 OR EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND ((table_name = 'official_broadcast_log'
                    AND column_name IN ('frozen_title', 'frozen_sub', 'intent_id', 'attempts', 'update_time'))
                OR (table_name = 'official_broadcast'
                    AND column_name IN ('audience_frozen', 'client_request_id', 'request_owner', 'request_hash'))))
 OR EXISTS (SELECT 1 FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_creation');

-- Only widen the existing state slot; never rewrite either protocol's rows or timestamps.
ALTER TABLE official_broadcast_log
 MODIFY COLUMN delivery_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
 ADD COLUMN frozen_title VARCHAR(128) NULL,
 ADD COLUMN frozen_sub TEXT NULL,
 ADD COLUMN intent_id BIGINT NULL,
 ADD COLUMN attempts INT NOT NULL DEFAULT 0,
 ADD COLUMN update_time DATETIME(3) NULL;
ALTER TABLE official_broadcast ADD COLUMN audience_frozen TINYINT NOT NULL DEFAULT 0;
ALTER TABLE official_broadcast
 ADD COLUMN client_request_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN request_owner VARCHAR(64) NULL,
 ADD COLUMN request_hash VARCHAR(64) NULL,
 ADD UNIQUE KEY uk_broadcast_request(request_owner,client_request_id);
CREATE TABLE official_broadcast_creation (
 request_owner VARCHAR(64) NOT NULL, request_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL, broadcast_id BIGINT NULL,
 PRIMARY KEY(request_owner,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DROP TEMPORARY TABLE tmp_broadcast_union_guard;
-- DDL is not atomic. On any failure retain all receipts, keep workers stopped and inspect the exact prefix.
-- No retry, lease, audience backfill, success promotion, reached_time guess or history deletion is performed.
