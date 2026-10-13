-- 补齐商家退出供给与补位申请的已发布 Mapper 所需列。
-- 依赖：migration_perk_ledger_20260716.sql、migration_terms_mode_20260716.sql、
--       migration_chapter_merchant_node.sql、migration_chapter_recruitment_20260722.sql。
-- 只补结构；历史状态、份数、条款、审批、时间和身份不回填、不修改。
-- 新列均 NULL：不把历史记录伪造成已退出、已确认必选章责任或补位关系。
-- 既有正确列可重放，部分完成可续跑；已有错误形态在任何 DDL 前 fail closed。
-- 部署顺序：先完成迁移验收，再部署使用这些列的 jar。
-- 生产执行须先只读预检并取得用户确认；本文件不连接生产数据库。

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name IN ('merchant_chapter_offer', 'merchant_chapter_application')
      AND engine = 'InnoDB') = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND ((table_name = 'merchant_chapter_offer'
         AND column_name IN ('withdraw_reason', 'withdraw_required', 'replacement_of_offer_id'))
     OR (table_name = 'merchant_chapter_application' AND column_name = 'replacement_of_offer_id'))
   AND NOT (is_nullable = 'YES' AND column_default IS NULL AND extra = ''
     AND ((column_name = 'withdraw_reason' AND data_type = 'varchar' AND character_maximum_length = 500)
       OR (column_name = 'withdraw_required' AND data_type = 'tinyint')
       OR (column_name = 'replacement_of_offer_id' AND column_type = 'bigint')))) = 0,
  1, JSON_EXTRACT('ASSERT_FAILED__merchant_offer_withdrawal_columns_before_ddl', '$')
) AS UNSIGNED) AS assertion_ok;

SET @offer_withdraw_reason_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer'
       AND column_name = 'withdraw_reason'
);
SET @offer_withdraw_reason_sql := IF(
    @offer_withdraw_reason_exists = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN withdraw_reason VARCHAR(500) NULL DEFAULT NULL COMMENT ''商家主动退出理由；停用后保留原供给历史'', ALGORITHM=INSTANT',
    'SELECT ''merchant_chapter_offer.withdraw_reason already exists'''
);
PREPARE offer_withdraw_reason_stmt FROM @offer_withdraw_reason_sql;
EXECUTE offer_withdraw_reason_stmt;
DEALLOCATE PREPARE offer_withdraw_reason_stmt;

SET @offer_withdraw_required_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer'
       AND column_name = 'withdraw_required'
);
SET @offer_withdraw_required_sql := IF(
    @offer_withdraw_required_exists = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN withdraw_required TINYINT(1) NULL DEFAULT NULL COMMENT ''退出时必选章快照；NULL表示历史未记录'', ALGORITHM=INSTANT',
    'SELECT ''merchant_chapter_offer.withdraw_required already exists'''
);
PREPARE offer_withdraw_required_stmt FROM @offer_withdraw_required_sql;
EXECUTE offer_withdraw_required_stmt;
DEALLOCATE PREPARE offer_withdraw_required_stmt;

SET @offer_replacement_of_offer_id_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer'
       AND column_name = 'replacement_of_offer_id'
);
SET @offer_replacement_of_offer_id_sql := IF(
    @offer_replacement_of_offer_id_exists = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN replacement_of_offer_id BIGINT NULL DEFAULT NULL COMMENT ''本次承接所补位的原退出供给ID'', ALGORITHM=INSTANT',
    'SELECT ''merchant_chapter_offer.replacement_of_offer_id already exists'''
);
PREPARE offer_replacement_of_offer_id_stmt FROM @offer_replacement_of_offer_id_sql;
EXECUTE offer_replacement_of_offer_id_stmt;
DEALLOCATE PREPARE offer_replacement_of_offer_id_stmt;

SET @application_replacement_of_offer_id_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_application'
       AND column_name = 'replacement_of_offer_id'
);
SET @application_replacement_of_offer_id_sql := IF(
    @application_replacement_of_offer_id_exists = 0,
    'ALTER TABLE merchant_chapter_application ADD COLUMN replacement_of_offer_id BIGINT NULL DEFAULT NULL COMMENT ''本次申请所补位的原退出供给ID'', ALGORITHM=INSTANT',
    'SELECT ''merchant_chapter_application.replacement_of_offer_id already exists'''
);
PREPARE application_replacement_of_offer_id_stmt FROM @application_replacement_of_offer_id_sql;
EXECUTE application_replacement_of_offer_id_stmt;
DEALLOCATE PREPARE application_replacement_of_offer_id_stmt;

-- runner: assert=scalar-true
SELECT CAST(IF((SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND ((table_name = 'merchant_chapter_offer'
         AND column_name IN ('withdraw_reason', 'withdraw_required', 'replacement_of_offer_id'))
     OR (table_name = 'merchant_chapter_application' AND column_name = 'replacement_of_offer_id'))
   AND (is_nullable = 'YES' AND column_default IS NULL AND extra = ''
     AND ((column_name = 'withdraw_reason' AND data_type = 'varchar' AND character_maximum_length = 500)
       OR (column_name = 'withdraw_required' AND data_type = 'tinyint')
       OR (column_name = 'replacement_of_offer_id' AND column_type = 'bigint')))) = 4,
  1, JSON_EXTRACT('ASSERT_FAILED__merchant_offer_withdrawal_columns_after_ddl', '$')
) AS UNSIGNED) AS assertion_ok;
