-- 2026-11-13 通行证收费落地补强：T02 渠道身份冻结 / T03 发货确认补偿 / T04-T06 退款与人工复核 / T05 领取停用 / T08 售出范围快照 / T10 同会员串行锁。
-- 只新增列与表，不改既有迁移；可重复执行。生产执行必须走 chengyin-db-migrate。

CREATE TABLE IF NOT EXISTS platform_pass_member_lock (
  member_id bigint NOT NULL,
  PRIMARY KEY (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='T10 通行证按会员串行化的锁行';

SET @pp_ddl_0 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'xpay_product_id') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN xpay_product_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''T02 下单冻结的虚拟支付道具 ID''', 'SELECT 1');
PREPARE pp_stmt_0 FROM @pp_ddl_0;
EXECUTE pp_stmt_0;
DEALLOCATE PREPARE pp_stmt_0;
SET @pp_ddl_1 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'xpay_offer_id') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN xpay_offer_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''T02 下单冻结的 OfferID（非密钥）''', 'SELECT 1');
PREPARE pp_stmt_1 FROM @pp_ddl_1;
EXECUTE pp_stmt_1;
DEALLOCATE PREPARE pp_stmt_1;
SET @pp_ddl_2 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'delivery_status') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN delivery_status tinyint NOT NULL DEFAULT 0 COMMENT ''T03 渠道发货确认 0 无需/未知 1 待确认 2 已确认''', 'SELECT 1');
PREPARE pp_stmt_2 FROM @pp_ddl_2;
EXECUTE pp_stmt_2;
DEALLOCATE PREPARE pp_stmt_2;
SET @pp_ddl_3 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'delivery_attempts') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN delivery_attempts int NOT NULL DEFAULT 0 COMMENT ''T03 发货确认已尝试次数''', 'SELECT 1');
PREPARE pp_stmt_3 FROM @pp_ddl_3;
EXECUTE pp_stmt_3;
DEALLOCATE PREPARE pp_stmt_3;
SET @pp_ddl_4 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'delivery_next_time') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN delivery_next_time datetime(3) NULL COMMENT ''T03 下次补偿时间''', 'SELECT 1');
PREPARE pp_stmt_4 FROM @pp_ddl_4;
EXECUTE pp_stmt_4;
DEALLOCATE PREPARE pp_stmt_4;
SET @pp_ddl_5 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'coverage_snapshot') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN coverage_snapshot varchar(512) NULL COMMENT ''T08 售出时覆盖范围 ALL 或主题 ID 列表''', 'SELECT 1');
PREPARE pp_stmt_5 FROM @pp_ddl_5;
EXECUTE pp_stmt_5;
DEALLOCATE PREPARE pp_stmt_5;
SET @pp_ddl_6 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'refund_terms_ref') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN refund_terms_ref varchar(64) NULL COMMENT ''T08 售出时退款条款引用''', 'SELECT 1');
PREPARE pp_stmt_6 FROM @pp_ddl_6;
EXECUTE pp_stmt_6;
DEALLOCATE PREPARE pp_stmt_6;
SET @pp_ddl_7 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'refund_amount_fen') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN refund_amount_fen int NULL COMMENT ''T04/T06 渠道回查到的真实退款金额''', 'SELECT 1');
PREPARE pp_stmt_7 FROM @pp_ddl_7;
EXECUTE pp_stmt_7;
DEALLOCATE PREPARE pp_stmt_7;
SET @pp_ddl_8 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'refund_source') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN refund_source varchar(12) NULL COMMENT ''LOCAL 本地发起 / CHANNEL 渠道发起''', 'SELECT 1');
PREPARE pp_stmt_8 FROM @pp_ddl_8;
EXECUTE pp_stmt_8;
DEALLOCATE PREPARE pp_stmt_8;
SET @pp_ddl_9 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'review_status') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN review_status tinyint NOT NULL DEFAULT 0 COMMENT ''T06 人工复核 0 无 1 待审 2 同意 3 驳回''', 'SELECT 1');
PREPARE pp_stmt_9 FROM @pp_ddl_9;
EXECUTE pp_stmt_9;
DEALLOCATE PREPARE pp_stmt_9;
SET @pp_ddl_10 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'review_apply_reason') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN review_apply_reason varchar(255) NULL COMMENT ''T06 申请原因''', 'SELECT 1');
PREPARE pp_stmt_10 FROM @pp_ddl_10;
EXECUTE pp_stmt_10;
DEALLOCATE PREPARE pp_stmt_10;
SET @pp_ddl_11 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'review_usage_facts') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN review_usage_facts varchar(512) NULL COMMENT ''T06 使用事实''', 'SELECT 1');
PREPARE pp_stmt_11 FROM @pp_ddl_11;
EXECUTE pp_stmt_11;
DEALLOCATE PREPARE pp_stmt_11;
SET @pp_ddl_12 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'review_handler') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN review_handler varchar(64) NULL COMMENT ''T06 处理人''', 'SELECT 1');
PREPARE pp_stmt_12 FROM @pp_ddl_12;
EXECUTE pp_stmt_12;
DEALLOCATE PREPARE pp_stmt_12;
SET @pp_ddl_13 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'review_decision') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN review_decision varchar(255) NULL COMMENT ''T06 处理决定''', 'SELECT 1');
PREPARE pp_stmt_13 FROM @pp_ddl_13;
EXECUTE pp_stmt_13;
DEALLOCATE PREPARE pp_stmt_13;
SET @pp_ddl_14 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'review_time') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN review_time datetime(3) NULL COMMENT ''T06 处理时间''', 'SELECT 1');
PREPARE pp_stmt_14 FROM @pp_ddl_14;
EXECUTE pp_stmt_14;
DEALLOCATE PREPARE pp_stmt_14;
SET @pp_ddl_15 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order' AND column_name = 'review_channel_receipt') = 0,
    'ALTER TABLE platform_pass_order ADD COLUMN review_channel_receipt varchar(64) NULL COMMENT ''T06 渠道退款回执（退款单号）''', 'SELECT 1');
PREPARE pp_stmt_15 FROM @pp_ddl_15;
EXECUTE pp_stmt_15;
DEALLOCATE PREPARE pp_stmt_15;
SET @pp_ddl_16 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_claim' AND column_name = 'status') = 0,
    'ALTER TABLE platform_pass_claim ADD COLUMN status tinyint NOT NULL DEFAULT 1 COMMENT ''T05 1 有效 / 2 退款后停用''', 'SELECT 1');
PREPARE pp_stmt_16 FROM @pp_ddl_16;
EXECUTE pp_stmt_16;
DEALLOCATE PREPARE pp_stmt_16;
SET @pp_ddl_17 = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'platform_pass_claim' AND column_name = 'stopped_time') = 0,
    'ALTER TABLE platform_pass_claim ADD COLUMN stopped_time datetime(3) NULL COMMENT ''T05 停用时间''', 'SELECT 1');
PREPARE pp_stmt_17 FROM @pp_ddl_17;
EXECUTE pp_stmt_17;
DEALLOCATE PREPARE pp_stmt_17;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
          AND ((table_name = 'platform_pass_order' AND column_name IN ('xpay_product_id','xpay_offer_id','delivery_status','delivery_attempts','delivery_next_time','coverage_snapshot','refund_terms_ref','refund_amount_fen','refund_source','review_status','review_apply_reason','review_usage_facts','review_handler','review_decision','review_time','review_channel_receipt'))
            OR (table_name = 'platform_pass_claim' AND column_name IN ('status','stopped_time')))) = 18;
