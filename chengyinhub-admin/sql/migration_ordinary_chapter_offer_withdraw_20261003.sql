-- 普通章节供给退出与同价补位：补齐既有 Mapper 所需字段，不回填或改变供给状态。
-- 前置：perk_ledger_20260716。上线执行另行只读预检、确认目标库和批准。
SET @offer_withdraw_reason_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer'
      AND column_name = 'withdraw_reason'
);
SET @offer_withdraw_reason_sql := IF(@offer_withdraw_reason_exists = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN withdraw_reason VARCHAR(500) NULL COMMENT ''商家退出原因，保留原供给记录''',
    'SELECT ''merchant_chapter_offer.withdraw_reason already exists''');
PREPARE offer_withdraw_reason_stmt FROM @offer_withdraw_reason_sql;
EXECUTE offer_withdraw_reason_stmt;
DEALLOCATE PREPARE offer_withdraw_reason_stmt;

SET @offer_withdraw_required_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer'
      AND column_name = 'withdraw_required'
);
SET @offer_withdraw_required_sql := IF(@offer_withdraw_required_exists = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN withdraw_required TINYINT NULL COMMENT ''退出时章节是否必选的快照，存量为空''',
    'SELECT ''merchant_chapter_offer.withdraw_required already exists''');
PREPARE offer_withdraw_required_stmt FROM @offer_withdraw_required_sql;
EXECUTE offer_withdraw_required_stmt;
DEALLOCATE PREPARE offer_withdraw_required_stmt;

SET @offer_replacement_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer'
      AND column_name = 'replacement_of_offer_id'
);
SET @offer_replacement_sql := IF(@offer_replacement_exists = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN replacement_of_offer_id BIGINT NULL COMMENT ''所补位的原供给ID''',
    'SELECT ''merchant_chapter_offer.replacement_of_offer_id already exists''');
PREPARE offer_replacement_stmt FROM @offer_replacement_sql;
EXECUTE offer_replacement_stmt;
DEALLOCATE PREPARE offer_replacement_stmt;
