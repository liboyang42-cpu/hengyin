-- NF-30/NF-31/NF-32 后端准备：举报透明化、商家众包、场所安全感评分。
-- 仅建模与接口准备；本文件未在生产库执行，奖励流水也不接真实积分/资金发放。

CREATE TABLE IF NOT EXISTS community_report_case (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reporter_member_id BIGINT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    policy_version VARCHAR(64) NULL,
    reason_label_snapshot VARCHAR(100) NULL,
    priority_snapshot VARCHAR(16) NULL,
    first_response_minutes_snapshot INT NULL,
    description VARCHAR(2000) NOT NULL,
    evidence_asset_ids_json JSON NOT NULL,
    anonymity_policy VARCHAR(32) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    estimated_resolve_at DATETIME NULL,
    decision_summary VARCHAR(500) NULL,
    request_id VARCHAR(96) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_report_request (reporter_member_id, request_id),
    KEY idx_community_report_owner (reporter_member_id, stage, create_time),
    KEY idx_community_report_subject (target_type, target_id, stage),
    CONSTRAINT chk_community_report_target CHECK (
        target_type IN ('MEMBER', 'MERCHANT', 'CLUB', 'ACTIVITY', 'CONTENT', 'SAFETY_RATING')
    ),
    CONSTRAINT chk_community_report_anonymity CHECK (
        anonymity_policy IN ('SUBJECT_HIDDEN')
    ),
    CONSTRAINT chk_community_report_priority CHECK (
        priority_snapshot IN ('URGENT', 'STANDARD')
    ),
    CONSTRAINT chk_community_report_first_response CHECK (
        first_response_minutes_snapshot BETWEEN 1 AND 10080
    ),
    CONSTRAINT chk_community_report_stage CHECK (
        stage IN ('SUBMITTED', 'TRIAGED', 'INVESTIGATING', 'RESOLVED', 'CLOSED', 'APPEALED')
    ),
    CONSTRAINT chk_community_report_evidence CHECK (JSON_VALID(evidence_asset_ids_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区举报内部主表；举报人标识不得进入被举报方查询投影';

CREATE TABLE IF NOT EXISTS community_report_timeline (
    id BIGINT NOT NULL AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_stage VARCHAR(32) NULL,
    to_stage VARCHAR(32) NOT NULL,
    public_note VARCHAR(500) NOT NULL,
    internal_note VARCHAR(1000) NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_report_event (report_id, event_key),
    KEY idx_community_report_timeline (report_id, create_time, id),
    KEY idx_community_report_first_response (actor_type, event_type, report_id, create_time),
    CONSTRAINT chk_community_report_actor CHECK (
        actor_type IN ('REPORTER', 'SUBJECT', 'PLATFORM', 'SYSTEM')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='举报阶段时间线；主体投影只读取public_note';

CREATE TABLE IF NOT EXISTS community_report_appeal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    appellant_member_id BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    request_id VARCHAR(96) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_report_appeal_request (appellant_member_id, request_id),
    KEY idx_community_report_appeal (report_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='举报申诉受理回执；并发是否成功由举报主表阶段CAS决定';

CREATE TABLE IF NOT EXISTS merchant_contribution (
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    contributor_member_id BIGINT NOT NULL,
    contribution_type VARCHAR(24) NOT NULL,
    field_changes_json JSON NOT NULL,
    base_values_json JSON NOT NULL,
    issue_type VARCHAR(32) NULL,
    description VARCHAR(2000) NOT NULL,
    evidence_asset_ids_json JSON NOT NULL,
    idempotency_key VARCHAR(96) NOT NULL,
    scope_key CHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    review_status VARCHAR(24) NOT NULL,
    review_reason VARCHAR(500) NULL,
    can_supplement TINYINT(1) NOT NULL DEFAULT 0,
    reviewed_by_user_id BIGINT NULL,
    reviewed_at DATETIME NULL,
    merged_at DATETIME NULL,
    version INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_contribution_request (contributor_member_id, idempotency_key),
    UNIQUE KEY uk_merchant_contribution_scope (contributor_member_id, merchant_id, scope_key),
    KEY idx_merchant_contribution_owner (contributor_member_id, create_time),
    KEY idx_merchant_contribution_review (review_status, create_time),
    CONSTRAINT chk_merchant_contribution_type CHECK (
        contribution_type IN ('INFO_UPDATE', 'ISSUE_REPORT')
    ),
    CONSTRAINT chk_merchant_contribution_status CHECK (
        review_status IN ('PENDING', 'NEEDS_EVIDENCE', 'APPROVED', 'REJECTED', 'MERGED')
    ),
    CONSTRAINT chk_merchant_contribution_fields CHECK (JSON_VALID(field_changes_json)),
    CONSTRAINT chk_merchant_contribution_base CHECK (JSON_VALID(base_values_json)),
    CONSTRAINT chk_merchant_contribution_evidence CHECK (JSON_VALID(evidence_asset_ids_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家信息贡献与问题上报';

CREATE TABLE IF NOT EXISTS merchant_contribution_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    contribution_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    reward_points INT NULL,
    request_id VARCHAR(96) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    expected_version INT NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_contribution_review_request (reviewer_user_id, request_id),
    UNIQUE KEY uk_merchant_contribution_review_version (contribution_id, expected_version),
    CONSTRAINT chk_merchant_contribution_review_action CHECK (
        action IN ('APPROVE', 'REJECT', 'NEEDS_EVIDENCE', 'MERGE')
    ),
    CONSTRAINT chk_merchant_contribution_reward_points CHECK (
        reward_points IS NULL OR reward_points > 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='众包审核不可变记录；expected_version与主表CAS同源';

CREATE TABLE IF NOT EXISTS merchant_contribution_supplement (
    id BIGINT NOT NULL AUTO_INCREMENT,
    contribution_id BIGINT NOT NULL,
    contributor_member_id BIGINT NOT NULL,
    description VARCHAR(2000) NOT NULL,
    evidence_asset_ids_json JSON NOT NULL,
    request_id VARCHAR(96) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    expected_version INT NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contribution_supplement_request (contributor_member_id, request_id),
    UNIQUE KEY uk_contribution_supplement_version (contribution_id, expected_version),
    CONSTRAINT chk_contribution_supplement_version CHECK (expected_version >= 0),
    CONSTRAINT chk_contribution_supplement_evidence CHECK (JSON_VALID(evidence_asset_ids_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='众包补证不可变记录；稳定请求键防重放，版本键防并发重复补证';

CREATE TABLE IF NOT EXISTS merchant_contribution_reward_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT,
    contribution_id BIGINT NOT NULL,
    contributor_member_id BIGINT NOT NULL,
    business_key VARCHAR(96) NOT NULL,
    status VARCHAR(16) NOT NULL,
    points INT NULL,
    external_ledger_no VARCHAR(96) NULL,
    credited_at DATETIME NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contribution_reward_contribution (contribution_id),
    UNIQUE KEY uk_contribution_reward_business (business_key),
    KEY idx_contribution_reward_owner (contributor_member_id, create_time),
    CONSTRAINT chk_contribution_reward_status CHECK (
        status IN ('PROVISIONAL', 'CREDITED', 'VOIDED')
    ),
    CONSTRAINT chk_contribution_reward_credit CHECK (
        (status = 'PROVISIONAL' AND credited_at IS NULL AND external_ledger_no IS NULL)
        OR (status = 'VOIDED' AND credited_at IS NULL AND external_ledger_no IS NULL)
        OR (status = 'CREDITED' AND points IS NOT NULL AND points > 0
            AND external_ledger_no IS NOT NULL AND credited_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='贡献奖励状态账；本批不接真实积分或资金账本';

CREATE TABLE IF NOT EXISTS merchant_safety_policy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    minimum_sample_count INT NOT NULL,
    window_days INT NOT NULL,
    neutral_minimum_score DECIMAL(4,3) NOT NULL,
    safe_minimum_score DECIMAL(4,3) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_merchant_safety_policy_active (status, id),
    CONSTRAINT chk_merchant_safety_policy CHECK (
        minimum_sample_count >= 2 AND window_days > 0
        AND neutral_minimum_score >= 1 AND safe_minimum_score > neutral_minimum_score
        AND safe_minimum_score <= 3
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全感聚合政策；本迁移不插入ACTIVE行，产品冻结口径前接口fail-closed';

CREATE TABLE IF NOT EXISTS merchant_safety_rating (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    overall_level VARCHAR(16) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    idempotency_key VARCHAR(96) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    rating_period CHAR(6) NOT NULL,
    eligibility_type VARCHAR(24) NOT NULL,
    eligibility_source_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_safety_request (member_id, idempotency_key),
    UNIQUE KEY uk_merchant_safety_period (member_id, merchant_id, rating_period),
    UNIQUE KEY uk_merchant_safety_eligibility (eligibility_type, eligibility_source_id),
    KEY idx_merchant_safety_window (merchant_id, create_time, member_id),
    CONSTRAINT chk_merchant_safety_level CHECK (
        overall_level IN ('SAFE', 'NEUTRAL', 'UNSAFE')
    ),
    CONSTRAINT chk_merchant_safety_period CHECK (
        rating_period REGEXP '^[0-9]{6}$'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场所安全感三档评分；每人每店每自然月仅一条';

CREATE TABLE IF NOT EXISTS merchant_safety_rating_dimension (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rating_id BIGINT NOT NULL,
    dimension_code VARCHAR(32) NOT NULL,
    level VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_safety_dimension (rating_id, dimension_code),
    KEY idx_merchant_safety_dimension_code (dimension_code, rating_id),
    CONSTRAINT chk_merchant_safety_dimension CHECK (
        dimension_code IN ('OVERALL')
    ),
    CONSTRAINT chk_merchant_safety_dimension_level CHECK (
        level IN ('SAFE', 'NEUTRAL', 'UNSAFE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全感评分维度明细';
