-- F11(FE-G16=B / FE-G22):自由探索店铺「参考结算」账。
-- 线上把每家店应分多少算完整、给各方看,直到「已线下结清」;钱不经过平台划给店铺,
-- 由场次收款人(主办方)与店铺线下结款后在线上标记。
--
-- 为什么单建一张表而不在 settlement_detail 上加标记:settlement_detail 的每一行都参与余额、
-- 提现与退款追回(RefundServiceImpl.allocateClawback)的汇总;参考账放在独立表里,
-- 从结构上保证它不会混进任何资金汇总。本表没有任何余额 / 提现 / 退款追回读者。
--
-- 列口径:
--   basis          PERK_REDEEMED(权益核销) | REVSHARE_HEAD(计酬人头) | TRAFFIC(引流,预留)
--                  | DISTRIBUTION(分销佣金,预留,本次不写)
--   supplier_kind  MERCHANT | AUTHOR(F10 作者自己承接;金额 0,直接 SETTLED_OFFLINE,备注「作者自营」)
--   merchant_id    店铺 member_id(AUTHOR 行 = 作者 member_id,与 merchant_chapter_offer.merchant_id 同口径)
--   payer_kind     场次收款人类型 CLUB(俱乐部场次,收款人 = 冻结时的主理人) | MEMBER(个人/商家发布场次)
--   payer_id       场次收款人 member_id(钱给人,不给俱乐部实体;与 CmsActivityServiceImpl.activityPayee 同一规则)
--   activity_id    场次;NULL = 无场次的主题级购票
--   chapter_id     章节;NULL = 旧合作单(coop_order)计酬,无章节归属
--   registration_id / redemption_id  来源报名 / 核销;整单退款时据此把未核销的 OPEN 行置 VOID
--   status         OPEN(待线下结款) | SETTLED_OFFLINE(已线下结清) | VOID(作废) | DISPUTED(店铺有异议)
--
-- 依赖:无外键;只新建本表。
-- 可重放:表已存在则跳过建表;已有错误形态在任何 DDL 前 fail closed。
-- 生产执行须先只读预检并取得用户确认;本文件不连接生产数据库。

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'fe_reference_settlement') = 0
  OR (
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema = DATABASE() AND table_name = 'fe_reference_settlement' AND engine = 'InnoDB') = 1
    AND (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'fe_reference_settlement'
        AND column_name IN ('id', 'topic_id', 'activity_id', 'chapter_id', 'offer_id', 'supplier_kind',
                            'merchant_id', 'payer_kind', 'payer_id', 'basis', 'quantity', 'unit_amount',
                            'amount', 'status', 'settled_at', 'settled_by', 'voucher_no', 'note',
                            'registration_id', 'redemption_id', 'idempotency_key', 'create_time',
                            'update_time')) = 23
    AND (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'fe_reference_settlement'
        AND index_name = 'uk_fe_ref_idempotency' AND non_unique = 0 AND column_name = 'idempotency_key') = 1
  ),
  1, JSON_EXTRACT('ASSERT_FAILED__fe_reference_settlement_before_ddl', '$')
) AS UNSIGNED) AS assertion_ok;

CREATE TABLE IF NOT EXISTS fe_reference_settlement (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    activity_id BIGINT NULL COMMENT '场次;NULL=无场次的主题级购票',
    chapter_id BIGINT NULL COMMENT '章节;NULL=旧合作单计酬',
    offer_id BIGINT NULL COMMENT 'merchant_chapter_offer.id;旧合作单计酬为 NULL',
    supplier_kind VARCHAR(16) NOT NULL DEFAULT 'MERCHANT' COMMENT 'MERCHANT|AUTHOR',
    merchant_id BIGINT NULL COMMENT '店铺 member_id;AUTHOR 行=作者 member_id',
    payer_kind VARCHAR(16) NOT NULL COMMENT '场次收款人类型 CLUB|MEMBER',
    payer_id BIGINT NOT NULL COMMENT '场次收款人 member_id',
    basis VARCHAR(16) NOT NULL COMMENT 'PERK_REDEEMED|REVSHARE_HEAD|TRAFFIC|DISTRIBUTION(预留)',
    quantity INT NOT NULL,
    unit_amount DECIMAL(12,2) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN|SETTLED_OFFLINE|VOID|DISPUTED',
    settled_at DATETIME NULL,
    settled_by BIGINT NULL COMMENT '标记结清的 member_id',
    voucher_no VARCHAR(64) NULL,
    note VARCHAR(200) NULL,
    registration_id BIGINT NULL,
    redemption_id BIGINT NULL,
    idempotency_key VARCHAR(96) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fe_ref_idempotency (idempotency_key),
    KEY idx_fe_ref_activity (activity_id),
    KEY idx_fe_ref_merchant (merchant_id, status),
    KEY idx_fe_ref_payer (payer_kind, payer_id, status),
    KEY idx_fe_ref_registration (registration_id),
    KEY idx_fe_ref_redemption (redemption_id),
    CONSTRAINT ck_fe_ref_basis CHECK (basis IN ('PERK_REDEEMED','REVSHARE_HEAD','TRAFFIC','DISTRIBUTION')),
    CONSTRAINT ck_fe_ref_status CHECK (status IN ('OPEN','SETTLED_OFFLINE','VOID','DISPUTED')),
    CONSTRAINT ck_fe_ref_supplier CHECK (supplier_kind IN ('MERCHANT','AUTHOR')),
    CONSTRAINT ck_fe_ref_payer CHECK (payer_kind IN ('CLUB','MEMBER')),
    CONSTRAINT ck_fe_ref_amount CHECK (quantity >= 0 AND unit_amount >= 0 AND amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='自由探索店铺参考结算（不入余额，线下结款）';

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'fe_reference_settlement' AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'fe_reference_settlement'
      AND ((column_name = 'idempotency_key' AND data_type = 'varchar' AND character_maximum_length = 96 AND is_nullable = 'NO')
        OR (column_name = 'amount' AND data_type = 'decimal' AND numeric_precision = 12 AND numeric_scale = 2 AND is_nullable = 'NO')
        OR (column_name = 'status' AND data_type = 'varchar' AND is_nullable = 'NO' AND column_default = 'OPEN')
        OR (column_name = 'payer_id' AND column_type = 'bigint' AND is_nullable = 'NO')
        OR (column_name = 'activity_id' AND column_type = 'bigint' AND is_nullable = 'YES'))) = 5
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'fe_reference_settlement'
      AND index_name = 'uk_fe_ref_idempotency' AND non_unique = 0 AND column_name = 'idempotency_key') = 1,
  1, JSON_EXTRACT('ASSERT_FAILED__fe_reference_settlement_after_ddl', '$')
) AS UNSIGNED) AS assertion_ok;
