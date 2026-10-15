-- Test-only frozen original badge intentions. Core finish/XP and each badge retain separate outcomes.
CREATE TABLE IF NOT EXISTS roam_badge_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    badge_code VARCHAR(64) NOT NULL,
    source_id BIGINT NOT NULL,
    source_type INT NOT NULL,
    badge_name VARCHAR(200) NOT NULL,
    icon_url VARCHAR(500) NULL,
    obtain_time DATETIME(3) NOT NULL,
    state VARCHAR(16) NOT NULL,
    delivered_at DATETIME(3) NULL,
    UNIQUE KEY uk_roam_badge_original (session_id, badge_code, source_id),
    KEY idx_roam_badge_pending (state, id),
    KEY idx_roam_badge_owner (member_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS roam_badge_reconcile_cursor (
    id TINYINT NOT NULL PRIMARY KEY,
    after_id BIGINT NOT NULL,
    through_id BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- readback: Both intent and durable cursor tables must exist on the selected scratch database.
-- runner: assert=scalar-true
SELECT COUNT(*) = 2 FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN ('roam_badge_delivery', 'roam_badge_reconcile_cursor');
