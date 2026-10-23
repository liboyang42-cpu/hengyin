-- CANDIDATE ONLY. Do not execute without integrator review and an approved migration window.
-- Stop all old/new broadcast workers and publishers before the DDL/backfill; keep stopped until new code starts.
-- One-time additive migration, not an idempotent replay. Preflight must verify the six columns do not exist.
-- Use a SQL duplicate-key guard, not only runner comments: JDBC must reject the same bad legacy rows.
CREATE TEMPORARY TABLE tmp_d_broadcast_delivery_guard (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_d_broadcast_delivery_guard (id) VALUES (1);
INSERT INTO tmp_d_broadcast_delivery_guard (id) SELECT 1 WHERE EXISTS (
    SELECT 1 FROM official_broadcast_log l
    WHERE l.channel IS NULL OR BINARY l.channel NOT IN (BINARY 'inapp', BINARY 'subscribe')
       OR l.reached IS NULL OR l.reached NOT IN (0, 1)
       OR l.clicked IS NULL OR l.clicked NOT IN (0, 1)
       OR l.id <= 0 OR l.broadcast_id <= 0 OR l.member_id <= 0
       OR NOT EXISTS (SELECT 1 FROM official_broadcast b WHERE b.id = l.broadcast_id)
) OR EXISTS (
    SELECT 1 FROM official_broadcast b WHERE b.status IS NULL OR b.status NOT IN (0, 1, 2, 3)
);

ALTER TABLE official_broadcast_log
    ADD COLUMN delivery_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN delivery_title TEXT NULL,
    ADD COLUMN delivery_sub TEXT NULL,
    ADD COLUMN failure_code VARCHAR(64) NULL,
    ADD COLUMN message_id BIGINT NULL,
    ADD COLUMN reached_time DATETIME NULL;

-- Only untouched legacy receipts in the just-added default state are eligible.
-- Legacy inapp reached=1 proves local send; reached=0 and every subscribe row stay UNKNOWN.
UPDATE official_broadcast_log
SET delivery_state = CASE WHEN BINARY channel = BINARY 'inapp' AND reached = 1 THEN 'SUCCESS' ELSE 'UNKNOWN' END,
    failure_code = CASE WHEN BINARY channel = BINARY 'inapp' AND reached = 1 THEN NULL ELSE 'LEGACY_UNVERIFIED' END
WHERE BINARY delivery_state = BINARY 'UNKNOWN'
  AND delivery_title IS NULL AND delivery_sub IS NULL AND failure_code IS NULL
  AND message_id IS NULL AND reached_time IS NULL
  AND BINARY channel IN (BINARY 'inapp', BINARY 'subscribe')
  AND reached IN (0, 1) AND clicked IN (0, 1);

-- Repair derived facts only for broadcasts with retained legacy evidence AND a derived discrepancy.
-- No-log broadcasts are outside this repair; draft/revoked status is preserved (their derived count may repair).
UPDATE official_broadcast b
SET b.reached_count = (SELECT COUNT(*) FROM official_broadcast_log l
                      WHERE l.broadcast_id = b.id AND l.channel = 'inapp'
                        AND l.reached = 1 AND l.delivery_state = 'SUCCESS'),
    b.status = CASE WHEN b.status = 2 AND EXISTS
                    (SELECT 1 FROM official_broadcast_log l WHERE l.broadcast_id = b.id
                     AND l.delivery_state != 'SUCCESS') THEN 1 ELSE b.status END
WHERE EXISTS (SELECT 1 FROM official_broadcast_log l WHERE l.broadcast_id = b.id)
  AND (NOT (b.reached_count <=> (SELECT COUNT(*) FROM official_broadcast_log l
                               WHERE l.broadcast_id = b.id AND l.channel = 'inapp'
                                 AND l.reached = 1 AND l.delivery_state = 'SUCCESS'))
       OR (b.status = 2 AND EXISTS (SELECT 1 FROM official_broadcast_log l
                                   WHERE l.broadcast_id = b.id AND l.delivery_state != 'SUCCESS')));

-- SQL postflight: skipped/partly transformed legacy rows and inconsistent aggregates must abort, never disappear.
INSERT INTO tmp_d_broadcast_delivery_guard (id) SELECT 1 WHERE EXISTS (
    SELECT 1 FROM official_broadcast_log l
    WHERE NOT (BINARY l.delivery_state <=> BINARY (CASE WHEN BINARY l.channel = BINARY 'inapp' AND l.reached = 1
                                                      THEN 'SUCCESS' ELSE 'UNKNOWN' END))
       OR NOT (l.failure_code <=> (CASE WHEN BINARY l.channel = BINARY 'inapp' AND l.reached = 1
                                       THEN NULL ELSE 'LEGACY_UNVERIFIED' END))
       OR l.delivery_title IS NOT NULL OR l.delivery_sub IS NOT NULL
       OR l.message_id IS NOT NULL OR l.reached_time IS NOT NULL
) OR EXISTS (
    SELECT 1 FROM official_broadcast b
    WHERE EXISTS (SELECT 1 FROM official_broadcast_log l WHERE l.broadcast_id = b.id)
      AND (NOT (b.reached_count <=> (SELECT COUNT(*) FROM official_broadcast_log l
                                   WHERE l.broadcast_id = b.id AND l.channel = 'inapp'
                                     AND l.reached = 1 AND l.delivery_state = 'SUCCESS'))
           OR (b.status = 2 AND EXISTS (SELECT 1 FROM official_broadcast_log l
                                       WHERE l.broadcast_id = b.id AND l.delivery_state != 'SUCCESS')))
);
DROP TEMPORARY TABLE tmp_d_broadcast_delivery_guard;
-- Original identity/reached/clicked/create_time and unique keys are never rewritten or deleted.
-- On any failure keep all broadcast writers stopped; ALTER is not transactional. Do not blindly rerun or drop receipts.
