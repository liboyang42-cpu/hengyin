-- 【草案 · 未批准 · 不得执行】DOC2 FXE Phase B/C(F3 Phase 2 分账打款)DDL 草案。
-- 前置:draft_migration_fxe_o07_payable_links.sql 已按正式迁移落地(本草案引用其 merchant_payable.source_event_*)。
-- 位置刻意在 docs/ 下;批准前不进迁移目录。落地方式同 Phase A 草案(改名、登记依赖图、chengyin-db-migrate 预检 + 用户确认)。
--
-- 设计要点(详见 FXE.md §4):
--   * settlement_claim **不加列、不动 7 列唯一键**:套票单位的经济事实用 fulfillment_type='BUNDLE_UNIT'
--     + fulfillment_id=explore_bundle_unit.id 表达(unit.id 本身全局唯一),避免 RV3 B-527-1 担心的「放宽唯一闸」。
--     这里只加一个枚举 CHECK,把今天的事实(REGISTRATION/REDEMPTION)与新值钉死,杜绝自由文本。
--   * 打款指令与月结批次 1:1,金额来自「批次冻结额 + 批次调整」,指令一旦 APPROVED 金额与指纹不可改。
--   * 本草案只建「记录打款指令与结果」的表,不含任何真实打款通道配置;通道(线下对公 / 微信分账)由负责人决策 D-6。

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
     AND table_name = 'merchant_payable' AND column_name IN ('source_event_type', 'source_event_id')) = 2
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
     AND table_name = 'merchant_payable_batch_adjustment') = 1
  AND (SELECT COUNT(*) FROM settlement_claim
     WHERE fulfillment_type NOT IN ('REGISTRATION', 'REDEMPTION', 'BUNDLE_UNIT')) = 0,
  1, JSON_EXTRACT('ASSERT_FAILED__fxe_f3p2_prerequisites', '$')
) AS UNSIGNED) AS assertion_ok;

-- 1. claim 履约类型枚举(只加 CHECK,不改列、不改唯一键)
SET @fxe_ck_exists := (SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE() AND table_name = 'settlement_claim' AND constraint_name = 'ck_settlement_claim_fulfillment');
SET @fxe_sql := IF(@fxe_ck_exists = 0,
  'ALTER TABLE settlement_claim ADD CONSTRAINT ck_settlement_claim_fulfillment CHECK (fulfillment_type IN (''REGISTRATION'', ''REDEMPTION'', ''BUNDLE_UNIT''))',
  'SELECT ''ck_settlement_claim_fulfillment already exists''');
PREPARE fxe_stmt FROM @fxe_sql; EXECUTE fxe_stmt; DEALLOCATE PREPARE fxe_stmt;

-- 2. 打款指令(每个月结批次至多一条生效指令)
CREATE TABLE IF NOT EXISTS merchant_payout_instruction (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_id BIGINT NOT NULL COMMENT 'merchant_payable_batch.id',
  merchant_member_id BIGINT NOT NULL,
  period_ym CHAR(7) NOT NULL,
  amount DECIMAL(12,2) NOT NULL COMMENT '= 批次冻结额 + SUM(merchant_payable_batch_adjustment.amount),生成时冻结',
  amount_fingerprint CHAR(64) NOT NULL COMMENT 'SHA-256(batch_id|amount|台账行 id 集合|调整行 id 集合),审批/提交前重算比对',
  channel VARCHAR(24) NOT NULL COMMENT 'OFFLINE_BANK 线下对公(记凭证) / WECHAT_PROFIT_SHARING 微信分账(须 D-6 批准)',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/APPROVED/SUBMITTED/SUCCEEDED/FAILED/CANCELLED',
  active_batch_key BIGINT GENERATED ALWAYS AS (IF(status IN ('DRAFT', 'APPROVED', 'SUBMITTED', 'SUCCEEDED'), batch_id, NULL)) STORED
    COMMENT '同一批次同时只许一条非终止指令',
  idempotency_key VARCHAR(128) NOT NULL COMMENT 'payout:{batchId}:{attempt}',
  external_ref VARCHAR(128) NULL COMMENT '对公凭证号 / 微信分账单号',
  maker VARCHAR(64) NOT NULL,
  checker VARCHAR(64) NULL,
  approved_at DATETIME NULL,
  submitted_at DATETIME NULL,
  finished_at DATETIME NULL,
  failure_reason VARCHAR(500) NULL,
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payout_idem (idempotency_key),
  UNIQUE KEY uk_payout_active_batch (active_batch_key),
  UNIQUE KEY uk_payout_external_ref (channel, external_ref),
  KEY idx_payout_status (status, create_time),
  CONSTRAINT ck_payout_status CHECK (status IN ('DRAFT', 'APPROVED', 'SUBMITTED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
  CONSTRAINT ck_payout_channel CHECK (channel IN ('OFFLINE_BANK', 'WECHAT_PROFIT_SHARING')),
  CONSTRAINT ck_payout_amount CHECK (amount > 0),
  CONSTRAINT ck_payout_maker_checker CHECK (checker IS NULL OR checker <> maker),
  CONSTRAINT ck_payout_approved CHECK (status IN ('DRAFT', 'CANCELLED') OR (checker IS NOT NULL AND approved_at IS NOT NULL)),
  CONSTRAINT ck_payout_succeeded CHECK (status <> 'SUCCEEDED' OR external_ref IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FXE F3P2:商家应付打款指令(与月结批次 1:1,金额冻结)';

-- 3. 打款指令事件(append-only 留痕;状态迁移与本表同事务)
CREATE TABLE IF NOT EXISTS merchant_payout_instruction_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  instruction_id BIGINT NOT NULL,
  from_status VARCHAR(16) NOT NULL,
  to_status VARCHAR(16) NOT NULL,
  actor VARCHAR(64) NOT NULL,
  detail_json VARCHAR(2000) NULL COMMENT '通道回执摘要;不得含密钥/证书/完整银行账号',
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payout_event_transition (instruction_id, from_status, to_status),
  KEY idx_payout_event_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FXE F3P2:打款指令状态迁移留痕';

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE()
     AND table_name = 'settlement_claim' AND constraint_name = 'ck_settlement_claim_fulfillment') = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
     AND table_name = 'settlement_claim' AND index_name = 'uk_settlement_claim_key') = 7
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
     AND table_name = 'merchant_payout_instruction' AND index_name = 'uk_payout_active_batch' AND non_unique = 0) = 1
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
     AND table_name = 'merchant_payout_instruction_event') = 1,
  1, JSON_EXTRACT('ASSERT_FAILED__fxe_f3p2_shape', '$')
) AS UNSIGNED) AS assertion_ok;
