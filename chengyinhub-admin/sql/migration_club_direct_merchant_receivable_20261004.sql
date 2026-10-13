-- 普通自由探索：俱乐部线下直付商家；仅记事实，不进入平台应付/余额/提现。
ALTER TABLE merchant_chapter_offer
    ADD COLUMN payer_club_id BIGINT NULL COMMENT '承接时冻结的直付俱乐部；NULL保留历史平台采购路径';

CREATE TABLE club_direct_merchant_receivable (
    id BIGINT NOT NULL AUTO_INCREMENT,
    claim_id BIGINT NOT NULL,
    redemption_id BIGINT NOT NULL,
    offer_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    payer_club_id BIGINT NOT NULL,
    merchant_member_id BIGINT NOT NULL,
    per_head_fee_snapshot DECIMAL(10,2) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/SETTLED/VOID/REFUND_DUE/REFUNDED',
    settled_at DATETIME NULL,
    settled_by VARCHAR(64) NULL,
    pay_voucher_no VARCHAR(128) NULL,
    reversed_at DATETIME NULL,
    refunded_at DATETIME NULL,
    refunded_by VARCHAR(64) NULL,
    refund_voucher_no VARCHAR(128) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_direct_redemption (redemption_id),
    UNIQUE KEY uk_club_direct_claim (claim_id),
    KEY idx_club_direct_scope (topic_id, payer_club_id, merchant_member_id),
    CONSTRAINT ck_club_direct_fee CHECK (per_head_fee_snapshot >= 0),
    CONSTRAINT ck_club_direct_status CHECK (status IN ('OPEN','SETTLED','VOID','REFUND_DUE','REFUNDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部直付商家逐次核销应收；与平台采购月结隔离';
