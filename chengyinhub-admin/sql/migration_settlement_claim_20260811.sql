-- W5 合同四:统一 settlement_claim 唯一闸 + COOP_ORDER 原子归属明细。
-- 【只准备,本分支不落库】执行必须走 chengyin-db-migrate,且先跑 _precheck_settlement_claim_conflicts_20260811.sql。
-- MySQL 8:刻意不写 IF NOT EXISTS；对象已存在必须 fail-fast,禁止把半迁移静默当成功。

CREATE TABLE settlement_claim (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    commercial_context_type VARCHAR(32)  NOT NULL COMMENT 'TOPIC_EDITION/ACTIVITY',
    commercial_context_id   BIGINT       NOT NULL COMMENT '直接 owner id',
    fulfillment_type        VARCHAR(32)  NOT NULL COMMENT 'REGISTRATION/REDEMPTION',
    fulfillment_id          BIGINT       NOT NULL COMMENT '直接履约事实 id',
    beneficiary_role        VARCHAR(32)  NOT NULL COMMENT 'MERCHANT/CLUB/PLATFORM/CREATOR',
    beneficiary_id          BIGINT       NOT NULL COMMENT '按 role 规范化后的 id',
    service_unit            VARCHAR(48)  NOT NULL COMMENT 'CHAPTER_SUPPLY/MERCHANT_PARTICIPATION/ORGANIZER_LEAD',
    settlement_route        VARCHAR(32)  NOT NULL COMMENT 'NONE/CHAPTER_OFFER/COOP_ORDER/REGISTRATION_SPLIT',
    source_contract_type    VARCHAR(48)  NOT NULL COMMENT 'MERCHANT_CHAPTER_OFFER/COOP_ORDER/DIRECT_REGISTRATION',
    source_contract_id      BIGINT       NOT NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    create_time             DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_claim_key
        (commercial_context_type, commercial_context_id, fulfillment_type, fulfillment_id,
         beneficiary_role, beneficiary_id, service_unit),
    KEY idx_claim_source (source_contract_type, source_contract_id),
    KEY idx_claim_fulfillment (fulfillment_type, fulfillment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同四:原子经济请求唯一归属';

CREATE TABLE coop_settlement_detail (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    settlement_id   BIGINT         NOT NULL COMMENT 'coop_settlement 聚合头',
    claim_id        BIGINT         NOT NULL COMMENT 'REGISTRATION 原子 claim',
    registration_id BIGINT         NOT NULL,
    origin_amount   DECIMAL(15,2)  NOT NULL COMMENT '原始毛额,调整不得回写',
    create_time     DATETIME       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coop_settlement_detail_claim (claim_id),
    KEY idx_coop_settlement_detail_head (settlement_id, registration_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='COOP_ORDER 原子归属明细';

CREATE TABLE coop_settlement_adjustment (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    settlement_id BIGINT         NOT NULL,
    claim_id      BIGINT         NOT NULL,
    entry_kind    VARCHAR(24)    NOT NULL COMMENT 'ADJUSTMENT',
    event_key     VARCHAR(128)   NOT NULL COMMENT '不可变业务事件 id',
    amount        DECIMAL(15,2)  NOT NULL COMMENT '有符号调整额',
    status        VARCHAR(16)    NOT NULL COMMENT 'EXECUTED',
    create_time   DATETIME       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coop_adjustment_event (claim_id, entry_kind, event_key),
    KEY idx_coop_adjustment_head (settlement_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='COOP_ORDER append-only 调整事件';

ALTER TABLE merchant_payable
    ADD COLUMN claim_id BIGINT NULL COMMENT '存量待历史裁决;新写必填' AFTER id,
    ADD COLUMN event_key VARCHAR(128) NULL COMMENT 'ORIGIN/不可变调整事件' AFTER entry_type,
    ADD UNIQUE KEY uk_merchant_payable_claim_event (claim_id, entry_type, event_key);

ALTER TABLE settlement_detail
    ADD COLUMN claim_id BIGINT NULL COMMENT '存量待历史裁决;新写必填' AFTER id,
    ADD COLUMN entry_kind VARCHAR(24) NULL COMMENT 'ORIGIN/ADJUSTMENT' AFTER claim_id,
    ADD COLUMN event_key VARCHAR(128) NULL COMMENT 'ORIGIN/不可变调整事件' AFTER entry_kind,
    ADD UNIQUE KEY uk_settlement_detail_claim_event (claim_id, entry_kind, event_key);

ALTER TABLE coop_settlement
    ADD COLUMN settlement_route VARCHAR(32) NULL AFTER id,
    ADD COLUMN source_contract_type VARCHAR(48) NULL AFTER settlement_route,
    ADD COLUMN source_contract_id BIGINT NULL AFTER source_contract_type,
    ADD COLUMN commercial_context_type VARCHAR(32) NULL AFTER source_contract_id,
    ADD COLUMN commercial_context_id BIGINT NULL AFTER commercial_context_type,
    ADD COLUMN beneficiary_role VARCHAR(32) NULL AFTER commercial_context_id,
    ADD COLUMN beneficiary_id BIGINT NULL AFTER beneficiary_role,
    ADD COLUMN service_unit VARCHAR(48) NULL AFTER beneficiary_id,
    ADD KEY idx_coop_settlement_claim_header
        (settlement_route, source_contract_type, source_contract_id, beneficiary_role, beneficiary_id, service_unit);

-- 部署后回读:七列必须全部 non-null 且 UNIQUE 中不得含 settlement_route。
SELECT index_name, seq_in_index, column_name, non_unique
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'settlement_claim'
ORDER BY index_name, seq_in_index;

SELECT table_name, column_name, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('settlement_claim', 'coop_settlement_detail', 'coop_settlement_adjustment')
ORDER BY table_name, ordinal_position;
