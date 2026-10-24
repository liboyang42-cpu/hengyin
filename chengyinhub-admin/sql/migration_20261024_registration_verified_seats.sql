-- C: additive seat identity extension; never backfill verified seats from order_num/whole-order status.
-- Preflight: review existing multi-quantity group orders and reconciliation before enabling endpoints.
-- Run only through separately authorized migration. No UPDATE/DELETE of historical money/audit rows.
CREATE TABLE cms_registration_seat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    registration_id BIGINT NOT NULL COMMENT 'existing cms_registration.id, not a new order',
    seat_no INT NOT NULL COMMENT 'stable 1-based seat within original order_num',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0 issued identity only; 1 actually verified',
    topic_id BIGINT NULL,
    activity_id BIGINT NULL,
    verifier_member_id BIGINT NULL,
    verified_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_registration_seat (registration_id, seat_no),
    KEY idx_verified_group (topic_id, activity_id, status, verified_at),
    CONSTRAINT chk_registration_seat_number CHECK (seat_no > 0),
    CONSTRAINT chk_registration_seat_fact CHECK (
        (status = 0 AND topic_id IS NULL AND activity_id IS NULL AND verifier_member_id IS NULL AND verified_at IS NULL)
        OR (status = 1 AND topic_id IS NOT NULL AND topic_id > 0
            AND activity_id IS NOT NULL AND activity_id > 0
            AND verifier_member_id IS NOT NULL AND verifier_member_id > 0 AND verified_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='Actual seat verification facts extending existing registration/QR/group ledger';
-- Rollback: disable new multi-seat entry first; retain this table once any real fact exists.
-- Dropping it after use would erase fulfillment evidence and is not an application rollback.
