-- A原奖励券交付回执。仅批准开发测试目标验证；不表示生产DDL已授权或已执行。
CREATE TABLE IF NOT EXISTS coupon_source_grant (
    source_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    history_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    delivered_at DATETIME NULL,
    PRIMARY KEY (source_type, source_id),
    UNIQUE KEY uk_coupon_source_history (history_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
