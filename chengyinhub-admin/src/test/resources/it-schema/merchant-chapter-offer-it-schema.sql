-- 供给表结构来自 migration_perk_ledger_20260716.sql；后续字段由真实迁移补齐。
CREATE TABLE IF NOT EXISTS merchant_chapter_offer (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    topic_id     BIGINT       NOT NULL COMMENT '主题id(冗余,便于按主题聚合容量)',
    chapter_id   BIGINT       NOT NULL COMMENT '章节id',
    merchant_id  BIGINT       NOT NULL COMMENT '商家memberId(⚠️非mms_merchant.id)',
    perk_id      BIGINT       NOT NULL COMMENT '引用coop_perk.id(申报层),即本商家在这章供给的是哪份权益',
    quota_total  INT          NOT NULL DEFAULT 0 COMMENT '本商家在本章的接待上限(人次,不是权益种类数)',
    quota_used   INT          NOT NULL DEFAULT 0 COMMENT '已核销人次(核销时原子+1,撤销时原子-1)',
    valid_end    DATETIME     NULL COMMENT '供给有效期(空=不限)。⚠️到期不会自动翻status,算容量必须同时判它',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1生效 0失效(商家退出标失效,不删行)。⚠️生效判据=status=1 AND (valid_end IS NULL OR valid_end>NOW()),两条缺一即超卖',
    create_by    VARCHAR(64)  NULL,
    create_time  DATETIME     NULL,
    update_by    VARCHAR(64)  NULL,
    update_time  DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_offer_merchant_chapter (chapter_id, merchant_id),
    KEY idx_offer_capacity (chapter_id, status, valid_end, quota_total, quota_used),
    KEY idx_offer_topic (topic_id, status),
    KEY idx_offer_merchant (merchant_id, status),
    CONSTRAINT ck_offer_quota CHECK (quota_used >= 0 AND quota_used <= quota_total)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家×章节生效供给(执行层;申报层是coop_perk)';
