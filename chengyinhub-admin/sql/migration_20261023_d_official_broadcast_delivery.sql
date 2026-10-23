-- CANDIDATE ONLY. Do not execute without integrator review and an approved migration window.
-- Stop all old/new broadcast workers and publishers before the DDL/backfill; keep stopped until new code starts.
-- This is a one-time additive migration, not an idempotent replay. Preflight must verify no columns below exist.
ALTER TABLE official_broadcast_log
    ADD COLUMN delivery_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN delivery_title TEXT NULL,
    ADD COLUMN delivery_sub TEXT NULL,
    ADD COLUMN failure_code VARCHAR(64) NULL,
    ADD COLUMN message_id BIGINT NULL,
    ADD COLUMN reached_time DATETIME NULL;

-- Legacy inapp reached=1 follows a completed local send. Preserve that fact and all identities/clicks.
-- Legacy reached=0 may have committed a message before the receipt write failed: never make it retryable.
-- Legacy subscribe reached=1 was written even for false/fanout-only success, so it proves no WX delivery.
UPDATE official_broadcast_log
SET delivery_state = CASE WHEN channel = 'inapp' AND reached = 1 THEN 'SUCCESS' ELSE 'UNKNOWN' END,
    failure_code = CASE WHEN channel = 'inapp' AND reached = 1 THEN NULL ELSE 'LEGACY_UNVERIFIED' END;

-- Intentionally retain original reached/clicked/create_time values as historical evidence.
-- New reads require delivery_state=SUCCESS as well as reached=1.
-- No deletion, TTL, unique-index replacement, economic-table change, or automatic retry reset.

-- Repair only derived aggregate facts. Draft/revoked states remain untouched.
UPDATE official_broadcast b
SET b.reached_count = (SELECT COUNT(*) FROM official_broadcast_log l
                      WHERE l.broadcast_id = b.id AND l.channel = 'inapp'
                        AND l.reached = 1 AND l.delivery_state = 'SUCCESS'),
    b.status = CASE WHEN b.status = 2 AND EXISTS
                    (SELECT 1 FROM official_broadcast_log l WHERE l.broadcast_id = b.id
                     AND l.delivery_state != 'SUCCESS') THEN 1 ELSE b.status END;
