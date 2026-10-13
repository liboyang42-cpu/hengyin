-- 探店日第3批资金核心（不含尚未拍板的 Q/EXTRA_HOURS/PLAYER冷却/俱乐部对公资料）。
-- FREE_EXPLORE 的 product_type 真源是 ProductType.FREE_EXPLORE=2；
-- EXPLORE_PASS 的 purchase_kind 真源是 PurchaseKind.EXPLORE_PASS=3，二者禁止混用。

CREATE TABLE IF NOT EXISTS topic_edition_funds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    snapshot_kind VARCHAR(16) NOT NULL,
    snapshot_version BIGINT NOT NULL,
    is_current TINYINT NOT NULL,
    n_cash BIGINT NOT NULL,
    g_cash DECIMAL(14,2) NOT NULL,
    u_in DECIMAL(14,2) NOT NULL,
    g_earned DECIMAL(14,2) NOT NULL,
    u_out DECIMAL(14,2) NOT NULL,
    u_pending DECIMAL(14,2) NOT NULL,
    r_refund DECIMAL(14,2) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    idempotency_key VARCHAR(96) NOT NULL,
    computed_by VARCHAR(64) NOT NULL,
    computed_at DATETIME(3) NOT NULL,
    superseded_by VARCHAR(64) NULL,
    superseded_at DATETIME(3) NULL,
    supersede_reason VARCHAR(255) NULL,
    current_guard VARCHAR(96) GENERATED ALWAYS AS
        (CASE WHEN is_current = 1 THEN CONCAT(topic_id, ':', snapshot_kind) ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_edition_funds_version (topic_id, snapshot_kind, snapshot_version),
    UNIQUE KEY uk_edition_funds_current (current_guard),
    UNIQUE KEY uk_edition_funds_idempotency (idempotency_key),
    KEY idx_edition_funds_topic_time (topic_id, computed_at),
    CONSTRAINT ck_edition_funds_kind CHECK (snapshot_kind IN ('PROVISIONAL', 'FINAL')),
    CONSTRAINT ck_edition_funds_current CHECK (is_current IN (0, 1)),
    CONSTRAINT ck_edition_funds_amounts CHECK
        (n_cash >= 0 AND g_cash >= 0 AND u_in >= 0 AND g_earned >= 0
         AND u_out >= 0 AND u_pending >= 0 AND r_refund >= 0),
    CONSTRAINT ck_edition_funds_pending_subset CHECK (u_pending <= u_out),
    CONSTRAINT ck_edition_funds_conservation CHECK
        (g_cash + u_in = g_earned + u_out + r_refund)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='探店日期级资金快照（历史版本不可覆盖）';

CREATE TABLE IF NOT EXISTS topic_edition_cost (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    cost_version BIGINT NOT NULL,
    is_current TINYINT NOT NULL,
    tax_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    payment_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    material_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    kit_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    insurance_risk_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    acquisition_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    platform_labor_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    other_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    platform_onsite_hours DECIMAL(10,2) NULL,
    fixed_compensation DECIMAL(14,2) NOT NULL DEFAULT 0,
    bonuses DECIMAL(14,2) NOT NULL DEFAULT 0,
    break_even_fixed_cost DECIMAL(14,2) NULL,
    variable_cost_per_ticket DECIMAL(14,2) NULL,
    note VARCHAR(1000) NOT NULL,
    locked_at DATETIME(3) NULL,
    locked_by VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    current_guard BIGINT GENERATED ALWAYS AS (CASE WHEN is_current = 1 THEN topic_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_edition_cost_version (topic_id, cost_version),
    UNIQUE KEY uk_edition_cost_current (current_guard),
    CONSTRAINT ck_edition_cost_current CHECK (is_current IN (0, 1)),
    CONSTRAINT ck_edition_cost_nonnegative CHECK
        (tax_cost >= 0 AND payment_cost >= 0 AND material_cost >= 0 AND kit_cost >= 0
         AND insurance_risk_cost >= 0 AND acquisition_cost >= 0
         AND platform_labor_cost >= 0 AND other_cost >= 0
         AND fixed_compensation >= 0 AND bonuses >= 0
         AND (break_even_fixed_cost IS NULL OR break_even_fixed_cost >= 0)
         AND (variable_cost_per_ticket IS NULL OR variable_cost_per_ticket >= 0)
         AND (platform_onsite_hours IS NULL OR platform_onsite_hours >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='探店日每期平台八项成本输入';

CREATE TABLE IF NOT EXISTS club_edition_hours (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    club_id BIGINT NOT NULL,
    hour_kind VARCHAR(16) NOT NULL,
    planned_hours DECIMAL(10,2) NOT NULL,
    reported_actual_hours DECIMAL(10,2) NULL,
    confirmed_actual_hours DECIMAL(10,2) NULL,
    confirm_status VARCHAR(16) NOT NULL,
    reported_by BIGINT NULL,
    reported_at DATETIME(3) NULL,
    confirmed_by BIGINT NULL,
    confirmed_at DATETIME(3) NULL,
    evidence_note VARCHAR(1000) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_edition_hour_kind (topic_id, club_id, hour_kind),
    KEY idx_club_edition_hours_club (club_id, topic_id),
    CONSTRAINT ck_club_edition_hours_kind CHECK (hour_kind IN ('PREP', 'CONTENT', 'ONSITE', 'REVIEW')),
    CONSTRAINT ck_club_edition_hours_status CHECK
        (confirm_status IN ('UNREPORTED', 'REPORTED', 'CONFIRMED', 'REJECTED')),
    CONSTRAINT ck_club_edition_hours_nonnegative CHECK
        (planned_hours >= 0
         AND (reported_actual_hours IS NULL OR reported_actual_hours >= 0)
         AND (confirmed_actual_hours IS NULL OR confirmed_actual_hours >= 0)),
    CONSTRAINT ck_club_edition_hours_confirmed CHECK
        ((confirm_status = 'CONFIRMED' AND confirmed_actual_hours IS NOT NULL
          AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)
         OR confirm_status <> 'CONFIRMED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='探店日期级俱乐部四类计划/自报/确认工时';

SET @batch3_source_club_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration' AND column_name = 'source_club_id'
);
SET @batch3_source_club_sql := IF(
    @batch3_source_club_exists = 0,
    'ALTER TABLE cms_registration ADD COLUMN source_club_id BIGINT NULL COMMENT ''本期票源俱乐部；支付后冻结'' AFTER purchase_kind',
    'SELECT 1'
);
PREPARE batch3_source_club_stmt FROM @batch3_source_club_sql;
EXECUTE batch3_source_club_stmt;
DEALLOCATE PREPARE batch3_source_club_stmt;

SET @batch3_source_frozen_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration' AND column_name = 'source_frozen_at'
);
SET @batch3_source_frozen_sql := IF(
    @batch3_source_frozen_exists = 0,
    'ALTER TABLE cms_registration ADD COLUMN source_frozen_at DATETIME(3) NULL COMMENT ''票源支付冻结时刻'' AFTER source_club_id',
    'SELECT 1'
);
PREPARE batch3_source_frozen_stmt FROM @batch3_source_frozen_sql;
EXECUTE batch3_source_frozen_stmt;
DEALLOCATE PREPARE batch3_source_frozen_stmt;

SET @batch3_source_channel_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration' AND column_name = 'source_channel_code'
);
SET @batch3_source_channel_sql := IF(
    @batch3_source_channel_exists = 0,
    'ALTER TABLE cms_registration ADD COLUMN source_channel_code VARCHAR(64) NULL COMMENT ''本期售票窗内来源原文'' AFTER source_frozen_at',
    'SELECT 1'
);
PREPARE batch3_source_channel_stmt FROM @batch3_source_channel_sql;
EXECUTE batch3_source_channel_stmt;
DEALLOCATE PREPARE batch3_source_channel_stmt;

SET @batch3_source_index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration'
      AND index_name = 'idx_registration_source_club'
);
SET @batch3_source_index_sql := IF(
    @batch3_source_index_exists = 0,
    'ALTER TABLE cms_registration ADD INDEX idx_registration_source_club (source_club_id, source_frozen_at)',
    'SELECT 1'
);
PREPARE batch3_source_index_stmt FROM @batch3_source_index_sql;
EXECUTE batch3_source_index_stmt;
DEALLOCATE PREPARE batch3_source_index_stmt;
