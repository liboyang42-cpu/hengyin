-- SOURCE review only. Root owns migration/DAG approval; no DDL has been executed.
-- Preflight: cms_activity is InnoDB with original PK id; the new column must be absent.
-- Historical NULL stays unprovable; never backfill results from current orders or campaigns.
ALTER TABLE cms_activity ADD COLUMN cancellation_receipt_json JSON NULL
    COMMENT 'Original cancellation intent and committed full result; dedicated current-read/CAS only';
-- Readback: JSON NULL, no history data changes. Install before the dedicated cancellation readers.
-- Rollback: retain populated receipts and identities; do not drop them or re-enable duplicate dispatch.
