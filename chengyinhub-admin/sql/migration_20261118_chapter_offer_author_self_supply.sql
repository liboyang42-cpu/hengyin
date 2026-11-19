-- F10(FE-G13/FE-G14):自由探索「作者自己承接」商家位置。
-- 给 merchant_chapter_offer 增加供给方类型与作者 member_id。
--   supplier_kind      MERCHANT(默认,存量全部是商家承接)| AUTHOR(作者自己承接)
--   supplier_member_id AUTHOR 行 = 作者 member_id;MERCHANT 行为 NULL
-- ⚠️ merchant_id 保持 NOT NULL 不改:该列本来存的就是 member_id(非 mms_merchant.id),
--    AUTHOR 行写作者 member_id。这样 uk_offer_merchant_chapter(chapter_id, merchant_id)
--    同时挡住「作者自己承接 + 作者再用商家身份申请同一章」的重复承接,
--    所有按 merchant_id 联表的核销 / 容量 / 结算查询无需处理 NULL。
-- 依赖:merchant_chapter_offer 已存在(base_schema_20260818.sql)。
-- 存量不回填:DEFAULT 'MERCHANT' 即存量语义;不改任何既有行。
-- 可重放:列已存在则跳过;已有错误形态在任何 DDL 前 fail closed。
-- 生产执行须先只读预检并取得用户确认;本文件不连接生产数据库。

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer' AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer'
      AND column_name IN ('supplier_kind', 'supplier_member_id')
      AND NOT ((column_name = 'supplier_kind' AND data_type = 'varchar' AND character_maximum_length = 16
                AND is_nullable = 'NO' AND column_default = 'MERCHANT')
            OR (column_name = 'supplier_member_id' AND column_type = 'bigint' AND is_nullable = 'YES'
                AND column_default IS NULL))) = 0,
  1, JSON_EXTRACT('ASSERT_FAILED__chapter_offer_author_self_supply_before_ddl', '$')
) AS UNSIGNED) AS assertion_ok;

SET @offer_supplier_kind_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer'
       AND column_name = 'supplier_kind'
);
SET @offer_supplier_kind_sql := IF(
    @offer_supplier_kind_exists = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN supplier_kind VARCHAR(16) NOT NULL DEFAULT ''MERCHANT'' COMMENT ''MERCHANT|AUTHOR(F10 作者自己承接)''',
    'SELECT ''merchant_chapter_offer.supplier_kind already exists'''
);
PREPARE offer_supplier_kind_stmt FROM @offer_supplier_kind_sql;
EXECUTE offer_supplier_kind_stmt;
DEALLOCATE PREPARE offer_supplier_kind_stmt;

SET @offer_supplier_member_id_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer'
       AND column_name = 'supplier_member_id'
);
SET @offer_supplier_member_id_sql := IF(
    @offer_supplier_member_id_exists = 0,
    'ALTER TABLE merchant_chapter_offer ADD COLUMN supplier_member_id BIGINT NULL DEFAULT NULL COMMENT ''AUTHOR 行=作者 member_id;MERCHANT 行为 NULL'', ALGORITHM=INSTANT',
    'SELECT ''merchant_chapter_offer.supplier_member_id already exists'''
);
PREPARE offer_supplier_member_id_stmt FROM @offer_supplier_member_id_sql;
EXECUTE offer_supplier_member_id_stmt;
DEALLOCATE PREPARE offer_supplier_member_id_stmt;

-- runner: assert=scalar-true
SELECT CAST(IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_offer'
      AND ((column_name = 'supplier_kind' AND data_type = 'varchar' AND character_maximum_length = 16
            AND is_nullable = 'NO' AND column_default = 'MERCHANT')
        OR (column_name = 'supplier_member_id' AND column_type = 'bigint' AND is_nullable = 'YES'
            AND column_default IS NULL))) = 2,
  1, JSON_EXTRACT('ASSERT_FAILED__chapter_offer_author_self_supply_after_ddl', '$')
) AS UNSIGNED) AS assertion_ok;
