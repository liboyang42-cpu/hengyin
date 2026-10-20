-- DRAFT ONLY. Not executed. Review MySQL/version/schema before any separately approved deployment.
-- Append-only exact grant association. No backfill or inferred matching of old histories.
-- No foreign key to the removable current completion log: approved clawback/recompletion
-- creates a new log ID while each prior grant/history association must remain distinct.
CREATE TABLE cms_topic_completion_coupon_receipt (
    grant_log_id BIGINT NOT NULL PRIMARY KEY,
    topic_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    history_id BIGINT NOT NULL,
    granted_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_topic_completion_receipt_history (history_id),
    KEY ix_topic_completion_receipt_owner (member_id, grant_log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
