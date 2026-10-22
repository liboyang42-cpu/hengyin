-- DOC2 F3 Phase 1(决策 D-11)：settlement_claim 增加「单位」维度 unit_code。
-- 存量与现有写入方全部落默认值 ''(空串而非 NULL：MySQL 唯一键里 NULL 互不相等，用 NULL 会让存量 7 列键失去唯一性)。
-- 唯一键 uk_settlement_claim_key 由 7 列扩为 7 列 + unit_code：对存量行与所有现有写入方(均写 '')语义不变；
-- 只有将来按单位计提(Phase 2，未获批，本批不实现)才会写非空 unit_code。
-- 现有 SettlementClaimMapper 不引用 unit_code ⇒ 新 jar 先于/后于本迁移部署都可运行；但本迁移须先于 Phase 2 代码。
-- 【前置】settlement_claim(migration_settlement_claim_20260811.sql)。生产执行需单独确认：先备份、核对表结构，
--   本迁移会重建一条唯一索引(在线 DDL,INPLACE)，须在低峰窗口执行。
-- 幂等：列已存在且形状正确则跳过；唯一键已含 unit_code 则跳过；形状不对时 guard 主键冲突报错，不改不删。
DROP TEMPORARY TABLE IF EXISTS doc2_f3_claim_unit_guard;
CREATE TEMPORARY TABLE doc2_f3_claim_unit_guard(id TINYINT PRIMARY KEY);
INSERT INTO doc2_f3_claim_unit_guard VALUES(1);

INSERT INTO doc2_f3_claim_unit_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name='settlement_claim' AND engine='InnoDB') <> 1;

-- 同名列形状不对(非 varchar(32) NOT NULL DEFAULT '')不得当成已完成。
INSERT INTO doc2_f3_claim_unit_guard
SELECT 1 FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='settlement_claim' AND column_name='unit_code'
  AND NOT (column_type='varchar(32)' AND is_nullable='NO' AND column_default='');

-- 唯一键只认两种形状(先判形状再动 DDL，避免加完列才发现键不对而半途停下)：改前 7 列(重建) / 改后 7 列 + unit_code(跳过)；其他形状 guard 报错。
SET @doc2_f3_claim_uk := (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
  FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='settlement_claim'
    AND index_name='uk_settlement_claim_key' AND non_unique=0);
INSERT INTO doc2_f3_claim_unit_guard
SELECT 1 WHERE COALESCE(@doc2_f3_claim_uk, '') NOT IN (
  'commercial_context_type,commercial_context_id,fulfillment_type,fulfillment_id,beneficiary_role,beneficiary_id,service_unit',
  'commercial_context_type,commercial_context_id,fulfillment_type,fulfillment_id,beneficiary_role,beneficiary_id,service_unit,unit_code');
SET @doc2_f3_claim_unit_col := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE settlement_claim ADD COLUMN unit_code VARCHAR(32) NOT NULL DEFAULT '''' COMMENT ''结算单位码;空串=非按单位计提(存量与现有写入)''',
    'SELECT ''settlement_claim.unit_code already exists'' AS skipped')
  FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='settlement_claim' AND column_name='unit_code');
PREPARE doc2_f3_claim_unit_col_stmt FROM @doc2_f3_claim_unit_col;
EXECUTE doc2_f3_claim_unit_col_stmt;
DEALLOCATE PREPARE doc2_f3_claim_unit_col_stmt;

SET @doc2_f3_claim_uk_ddl := IF(@doc2_f3_claim_uk =
  'commercial_context_type,commercial_context_id,fulfillment_type,fulfillment_id,beneficiary_role,beneficiary_id,service_unit',
  'ALTER TABLE settlement_claim DROP INDEX uk_settlement_claim_key, ADD UNIQUE KEY uk_settlement_claim_key (commercial_context_type, commercial_context_id, fulfillment_type, fulfillment_id, beneficiary_role, beneficiary_id, service_unit, unit_code), ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT ''uk_settlement_claim_key already has unit_code'' AS skipped');
PREPARE doc2_f3_claim_uk_stmt FROM @doc2_f3_claim_uk_ddl;
EXECUTE doc2_f3_claim_uk_stmt;
DEALLOCATE PREPARE doc2_f3_claim_uk_stmt;
DROP TEMPORARY TABLE doc2_f3_claim_unit_guard;

-- runner: assert=scalar-true
SELECT COUNT(*)=8 AS assertion_ok FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='settlement_claim'
  AND index_name='uk_settlement_claim_key' AND non_unique=0
  AND (seq_in_index < 8 OR (seq_in_index = 8 AND column_name = 'unit_code'));
