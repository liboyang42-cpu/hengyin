-- 行业类型多选(owner 2026-10-22 批准)：新增 mms_merchant.category_ids 存逗号分隔的 sys_category.id。
-- category_id 保留为「主行业」(= category_ids 的第一个)，只认单类目的旧读者(搜索筛选/卡片图标)不受影响。
-- 前置：mms_merchant 在 sql/base_schema_20260818.sql。
-- 落库顺序：先手工跑本文件并通过末尾断言，再部署新 jar(新 jar 的 Mapper 会读写 category_ids)。
-- 幂等：ALTER 前查 information_schema，列已存在就跳过；同名但形状不符(非 varchar(255))fail-closed。
--   回填 UPDATE 只命中 category_ids 为空且 category_id>0 的行，回填后不再匹配，重跑安全。
DROP TEMPORARY TABLE IF EXISTS merchant_category_ids_20261022_guard;
CREATE TEMPORARY TABLE merchant_category_ids_20261022_guard(id TINYINT PRIMARY KEY);
INSERT INTO merchant_category_ids_20261022_guard VALUES(1);

INSERT INTO merchant_category_ids_20261022_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name='mms_merchant' AND engine='InnoDB') <> 1;

INSERT INTO merchant_category_ids_20261022_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='mms_merchant' AND column_name='category_ids'
    AND NOT (data_type='varchar' AND character_maximum_length=255 AND is_nullable='YES'));

SET @merchant_category_ids_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='mms_merchant' AND column_name='category_ids'),
  'SELECT 1',
  'ALTER TABLE mms_merchant ADD COLUMN category_ids VARCHAR(255) NULL DEFAULT NULL COMMENT ''行业类型多选(逗号分隔sys_category.id),category_id为其第一个'' AFTER category_id, ALGORITHM=INSTANT'
);
PREPARE merchant_category_ids_stmt FROM @merchant_category_ids_ddl;
EXECUTE merchant_category_ids_stmt;
DEALLOCATE PREPARE merchant_category_ids_stmt;

DROP TEMPORARY TABLE merchant_category_ids_20261022_guard;

-- 存量回填：单选时代的 category_id → category_ids。
UPDATE mms_merchant
   SET category_ids = CAST(category_id AS CHAR)
 WHERE (category_ids IS NULL OR category_ids = '')
   AND category_id IS NOT NULL AND category_id > 0;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='mms_merchant' AND column_name='category_ids'
           AND data_type='varchar' AND character_maximum_length=255) = 1
   AND (SELECT COUNT(*) FROM mms_merchant
         WHERE category_id > 0 AND (category_ids IS NULL OR category_ids = '')) = 0
       AS assertion_ok;
