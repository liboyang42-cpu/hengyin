-- SOURCE review only. Root owns migration/DAG approval; no DDL has been executed.
-- Preflight: cms_topic is InnoDB with original PK id; the column and receipt table must be absent.
-- Existing listing does not advance published_config_version; it is NOT a cancellation epoch.
ALTER TABLE cms_topic ADD COLUMN cancellation_epoch BIGINT NOT NULL DEFAULT 0
    COMMENT 'Dedicated listing CAS advances cursor; zero is not a historical financial receipt';
CREATE TABLE cms_topic_cancellation_receipt (
    topic_id BIGINT NOT NULL,
    cancellation_epoch BIGINT NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_json JSON NOT NULL,
    PRIMARY KEY(topic_id,cancellation_epoch),
    CONSTRAINT ck_topic_cancellation_epoch CHECK(cancellation_epoch>=0)
) ENGINE=InnoDB;
-- No history backfill. Cursor zero is only the cutover marker, never an invented original result.
-- Readback: cursor BIGINT NOT NULL default 0; exact (topic_id,cancellation_epoch) PK; JSON NOT NULL.
-- Install before listing/cancellation code. Keep old epoch rows across re-list; no TTL or lease reset.
-- Rollback retains both cursor and receipts. Pause/manual reconcile instead of erasing pending identity.
