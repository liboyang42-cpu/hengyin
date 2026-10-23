-- 【草案 · 未批准 · 不得执行】DOC2 FXE Phase A(O-07 计提链修复)DDL 草案。
-- 位置刻意放在 docs/ 下而不是 sql/ / chengyinhub-admin/sql/:未经负责人批准(FXE.md §5)前不进迁移目录、不登记依赖图。
-- 批准后的落地方式:改名为 chengyinhub-admin/sql/migration_<日期>_fxe_o07_payable_links.sql,
--   登记 ci/migration-dependencies.json,走 chengyin-db-migrate 只读预检 + 用户确认后执行。
--
-- 只做结构,不回填、不改任何既有行:
--   1) merchant_payable 能表达「非核销事件」:redemption_id 放宽为可空 + source_event_type/source_event_id + CHECK 二选一;
--   2) settlement_claim_event:claim 生命周期事件(撤销/重开/关闭)append-only,不再只有一个永远 ACTIVE 的 status;
--   3) merchant_payable_batch_adjustment:批次层的倒扣冲抵 / 负额结转,append-only,不再直接改 amount_total。
-- 幂等:列/索引/约束按 information_schema 判存在再 PREPARE;表用 CREATE TABLE IF NOT EXISTS + 末尾形状断言。
-- 已存在但形态不对 ⇒ 末尾断言 fail closed(JSON_EXTRACT 非法路径报错),不静默当成功。
-- 依赖:migration_settlement_claim_20260811.sql(claim_id/event_key 两列与 uk_merchant_payable_claim_event)。

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
     AND table_name IN ('merchant_payable', 'merchant_payable_batch', 'settlement_claim')) = 3
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'merchant_payable' AND column_name IN ('claim_id', 'event_key')) = 2,
  1, JSON_EXTRACT('ASSERT_FAILED__fxe_o07_prerequisites', '$')
) AS UNSIGNED) AS assertion_ok;

-- 1a. 来源事件两列(核销事件仍以 redemption_id 表达;非核销事件用这两列)
SET @fxe_src_type_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'merchant_payable' AND column_name = 'source_event_type');
SET @fxe_sql := IF(@fxe_src_type_exists = 0,
  'ALTER TABLE merchant_payable ADD COLUMN source_event_type VARCHAR(32) NULL COMMENT ''非核销来源:REFUND/BUNDLE_UNIT/MERCHANT_EXIT/MANUAL_ADJUST;NULL=核销事件(看 redemption_id)'' AFTER redemption_id',
  'SELECT ''merchant_payable.source_event_type already exists''');
PREPARE fxe_stmt FROM @fxe_sql; EXECUTE fxe_stmt; DEALLOCATE PREPARE fxe_stmt;

SET @fxe_src_id_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'merchant_payable' AND column_name = 'source_event_id');
SET @fxe_sql := IF(@fxe_src_id_exists = 0,
  'ALTER TABLE merchant_payable ADD COLUMN source_event_id BIGINT NULL COMMENT ''来源事件 id(refund_application.id / explore_bundle_unit.id / ...)'' AFTER source_event_type',
  'SELECT ''merchant_payable.source_event_id already exists''');
PREPARE fxe_stmt FROM @fxe_sql; EXECUTE fxe_stmt; DEALLOCATE PREPARE fxe_stmt;

-- 1b. redemption_id 放宽为可空(只在仍是 NOT NULL 时改;comment 原样保留语义并补一句)
SET @fxe_red_not_null := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'merchant_payable' AND column_name = 'redemption_id' AND is_nullable = 'NO');
SET @fxe_sql := IF(@fxe_red_not_null = 1,
  'ALTER TABLE merchant_payable MODIFY redemption_id BIGINT NULL COMMENT ''回指核销流水 redemption.id;REVERSAL 行指向被冲的那笔核销;非核销事件为 NULL(须有 source_event_*)''',
  'SELECT ''merchant_payable.redemption_id already nullable''');
PREPARE fxe_stmt FROM @fxe_sql; EXECUTE fxe_stmt; DEALLOCATE PREPARE fxe_stmt;

-- 1c. 二选一:要么是核销事件,要么有完整来源事件
SET @fxe_ck_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE() AND table_name = 'merchant_payable' AND constraint_name = 'ck_payable_source');
SET @fxe_sql := IF(@fxe_ck_exists = 0,
  'ALTER TABLE merchant_payable ADD CONSTRAINT ck_payable_source CHECK ((redemption_id IS NOT NULL AND source_event_type IS NULL AND source_event_id IS NULL) OR (redemption_id IS NULL AND source_event_type IS NOT NULL AND source_event_id IS NOT NULL))',
  'SELECT ''ck_payable_source already exists''');
PREPARE fxe_stmt FROM @fxe_sql; EXECUTE fxe_stmt; DEALLOCATE PREPARE fxe_stmt;

SET @fxe_idx_exists := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'merchant_payable' AND index_name = 'idx_payable_source_event');
SET @fxe_sql := IF(@fxe_idx_exists = 0,
  'ALTER TABLE merchant_payable ADD KEY idx_payable_source_event (source_event_type, source_event_id)',
  'SELECT ''idx_payable_source_event already exists''');
PREPARE fxe_stmt FROM @fxe_sql; EXECUTE fxe_stmt; DEALLOCATE PREPARE fxe_stmt;

-- 2. claim 生命周期事件(append-only)。settlement_claim.status 仍保留为「当前态」投影,由同事务 CAS 维护。
CREATE TABLE IF NOT EXISTS settlement_claim_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  claim_id BIGINT NOT NULL COMMENT 'settlement_claim.id',
  event_type VARCHAR(16) NOT NULL COMMENT 'REVERSED 履约被撤销 / REOPENED 撤销被纠正 / CLOSED 结清归档',
  event_key VARCHAR(128) NOT NULL COMMENT '不可变业务事件键,如 REDEMPTION_REVERSED:{redemptionId}',
  from_status VARCHAR(16) NOT NULL,
  to_status VARCHAR(16) NOT NULL,
  reason VARCHAR(255) NULL,
  actor VARCHAR(64) NOT NULL COMMENT 'sys_user.user_name 或 SYSTEM',
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_claim_event (claim_id, event_type, event_key),
  KEY idx_claim_event_time (create_time),
  CONSTRAINT ck_claim_event_type CHECK (event_type IN ('REVERSED', 'REOPENED', 'CLOSED')),
  CONSTRAINT ck_claim_event_status CHECK (from_status IN ('ACTIVE', 'REVERSED', 'CLOSED')
                                          AND to_status IN ('ACTIVE', 'REVERSED', 'CLOSED')
                                          AND from_status <> to_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FXE:settlement_claim 生命周期事件(append-only)';

-- 3. 批次层调整(append-only)。批次应付 = 台账净额 + SUM(本表 amount);amount_total 只在生成时冻结一次,不再被改。
CREATE TABLE IF NOT EXISTS merchant_payable_batch_adjustment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_id BIGINT NOT NULL COMMENT 'merchant_payable_batch.id(被调整的批次)',
  merchant_member_id BIGINT NOT NULL COMMENT '冗余,便于按商家对账;必须等于批次的 merchant_member_id',
  kind VARCHAR(24) NOT NULL COMMENT 'CLAWBACK_OFFSET 倒扣冲抵(负) / CARRY_OUT 负额结出(正,把本批归零) / CARRY_IN 负额结入(负)',
  source_type VARCHAR(32) NOT NULL COMMENT 'MERCHANT_CLAWBACK / BATCH_CARRY',
  source_id BIGINT NOT NULL COMMENT 'merchant_clawback_application.id / 结出方 batch_id',
  amount DECIMAL(12,2) NOT NULL COMMENT '有符号:对本批应付的增减',
  idempotency_key VARCHAR(128) NOT NULL COMMENT 'clawback:{applicationId} / carry-out:{fromBatchId} / carry-in:{fromBatchId}->{toBatchId}',
  created_by VARCHAR(64) NOT NULL,
  approved_by VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_batch_adjustment_idem (idempotency_key),
  UNIQUE KEY uk_batch_adjustment_source (kind, source_type, source_id, batch_id),
  KEY idx_batch_adjustment_batch (batch_id),
  CONSTRAINT ck_batch_adjustment_kind CHECK (kind IN ('CLAWBACK_OFFSET', 'CARRY_OUT', 'CARRY_IN')),
  CONSTRAINT ck_batch_adjustment_sign CHECK ((kind = 'CARRY_OUT' AND amount > 0) OR (kind <> 'CARRY_OUT' AND amount < 0)),
  CONSTRAINT ck_batch_adjustment_maker_checker CHECK (approved_by <> created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FXE:应付批次调整(倒扣冲抵/负额结转),append-only';

-- 末尾形状断言:三处结构都在且形态正确,否则 fail closed。
-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_payable'
     AND ((column_name = 'redemption_id' AND is_nullable = 'YES' AND data_type = 'bigint')
       OR (column_name = 'source_event_type' AND is_nullable = 'YES' AND data_type = 'varchar' AND character_maximum_length = 32)
       OR (column_name = 'source_event_id' AND is_nullable = 'YES' AND data_type = 'bigint'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE()
     AND table_name = 'merchant_payable' AND constraint_name = 'ck_payable_source') = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
     AND table_name = 'settlement_claim_event' AND index_name = 'uk_claim_event' AND non_unique = 0) = 3
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
     AND table_name = 'merchant_payable_batch_adjustment' AND index_name = 'uk_batch_adjustment_idem' AND non_unique = 0) = 1,
  1, JSON_EXTRACT('ASSERT_FAILED__fxe_o07_shape', '$')
) AS UNSIGNED) AS assertion_ok;
