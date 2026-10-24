-- G cash evidence EXPAND ONLY; no production execution/worker/payment activation.
-- Requires MySQL >=8.0.16, strict mode, FK checks; no existing ledger data is rewritten.
-- Preserve uk_settle_order, uk_coop_adjustment_event and claim_id NOT NULL.
-- DDL auto-commits: dirty-data preflight fails BEFORE first DDL; DML rollback is tested separately.
-- Newly inserted platform rows must explicitly name ACCOUNTING; old writers remain fail-closed.
-- root must register canonical dependencies/assertion/readback before any controlled apply.
-- runner: assert=scalar-true
SELECT CAST(IF(
  @@foreign_key_checks=1
  AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@sql_mode)>0)
  AND CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)=8
  AND (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)>0
       OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=16)
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
       AND table_name IN ('coop_settlement','coop_settlement_adjustment','cms_registration','cms_topic','ums_member','refund_application')
       AND engine='InnoDB')=6
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
       AND table_name IN ('coop_settlement_cash_close','coop_settlement_cash_contribution','coop_settlement_cash_event'))=0
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
       AND ((table_name='coop_settlement' AND column_name IN ('cash_head_kind','cash_close_id','cash_member_id','cash_initiator_member_id','cash_global_line_type'))
         OR (table_name='coop_settlement_adjustment' AND column_name IN ('cash_close_id','cash_event_id'))))=0
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
       AND ((table_name='coop_settlement' AND column_name='merchant_id')
         OR (table_name='coop_settlement_adjustment' AND column_name='claim_id'))
       AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO')=2
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
       AND table_name='ums_member' AND column_name='id' AND data_type='bigint' AND column_type LIKE '%unsigned%')=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
       AND ((table_name='coop_settlement' AND index_name IN ('idx_g_cash_head_member','idx_g_cash_head_close','idx_g_cash_head_initiator','uk_g_cash_head_id_close','uk_g_cash_global_head'))
         OR (table_name='coop_settlement_adjustment' AND index_name IN ('idx_g_cash_adjustment_event','idx_g_cash_adjustment_head','uk_g_cash_adjustment_event'))))=0
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
       AND constraint_name IN ('fk_g_cash_close_member','ck_g_cash_close','fk_g_cash_contribution_close','fk_g_cash_contribution_registration','fk_g_cash_contribution_payer','ck_g_cash_contribution','fk_g_cash_event_refund','fk_g_cash_event_contribution','ck_g_cash_event','fk_g_cash_head_member','fk_g_cash_head_close','fk_g_cash_head_initiator','ck_g_cash_head_payee','fk_g_cash_adjustment_event','fk_g_cash_adjustment_head','ck_g_cash_adjustment_link'))=0
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
       AND table_name='coop_settlement' AND index_name='uk_settle_order' AND non_unique=0 AND column_name='coop_order_id')=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
       AND table_name='coop_settlement_adjustment' AND index_name='uk_coop_adjustment_event' AND non_unique=0)=3
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
       AND table_name='coop_settlement' AND index_name='uk_settle_order' AND non_unique=0)=1
  AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics
       WHERE table_schema=DATABASE() AND table_name='coop_settlement_adjustment'
       AND index_name='uk_coop_adjustment_event' AND non_unique=0)='claim_id,entry_kind,event_key'
  AND (SELECT COUNT(*) FROM coop_settlement h LEFT JOIN ums_member m ON m.id=h.merchant_id
       WHERE h.merchant_id IS NULL OR h.merchant_id<=0 OR m.id IS NULL
          OR h.payee_type='platform' OR h.line_type IN ('PLATFORM','DEPOSIT_DEDUCTION'))=0
  AND (SELECT COUNT(*) FROM coop_settlement_adjustment a LEFT JOIN coop_settlement h ON h.id=a.settlement_id
       WHERE a.claim_id IS NULL OR a.claim_id<=0 OR h.id IS NULL)=0,
  1, JSON_EXTRACT('ASSERT_FAILED__g_cash_evidence_before_ddl','$')
) AS UNSIGNED) AS assertion_ok;

CREATE TABLE coop_settlement_cash_close (
  id BIGINT NOT NULL AUTO_INCREMENT,
  topic_id BIGINT NOT NULL,
  commercial_context_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  commercial_context_id BIGINT NOT NULL,
  close_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  initiator_member_id BIGINT UNSIGNED NULL,
  original_sales_basis DECIMAL(15,2) NOT NULL,
  original_contribution_count INT NOT NULL,
  platform_rate DECIMAL(10,6) NULL,
  guarantee_base_rate DECIMAL(10,6) NULL,
  guarantee_step_rate DECIMAL(10,6) NULL,
  guarantee_max_merchant INT NULL,
  original_verified_merchant_count INT NOT NULL,
  origin_calculation_version VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  contract_snapshot JSON NOT NULL,
  source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  closed_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_g_cash_close_topic(topic_id),
  UNIQUE KEY uk_g_cash_close_id_topic(id,topic_id),
  UNIQUE KEY uk_g_cash_close_id_initiator(id,initiator_member_id),
  CONSTRAINT fk_g_cash_close_member FOREIGN KEY(initiator_member_id) REFERENCES ums_member(id),
  CONSTRAINT ck_g_cash_close CHECK (
    CASE WHEN BINARY commercial_context_type=BINARY 'TOPIC_EDITION' AND commercial_context_id=topic_id
      AND topic_id>0 AND original_contribution_count>=0 AND original_verified_merchant_count>=0
      AND (initiator_member_id IS NULL OR initiator_member_id>0)
      AND (guarantee_max_merchant IS NULL OR guarantee_max_merchant>=0)
      AND (platform_rate IS NULL OR platform_rate BETWEEN 0 AND 1)
      AND (guarantee_base_rate IS NULL OR guarantee_base_rate BETWEEN 0 AND 1)
      AND (guarantee_step_rate IS NULL OR guarantee_step_rate BETWEEN 0 AND 1)
      AND ((BINARY close_kind=BINARY 'POSITIVE_SALES' AND original_sales_basis>0
            AND original_contribution_count>0 AND initiator_member_id IS NOT NULL
            AND platform_rate IS NOT NULL AND guarantee_base_rate IS NOT NULL
            AND guarantee_step_rate IS NOT NULL AND guarantee_max_merchant IS NOT NULL)
        OR (BINARY close_kind=BINARY 'ZERO_SALES' AND original_sales_basis=0))
      AND CHAR_LENGTH(origin_calculation_version)>0
      AND JSON_TYPE(contract_snapshot)='OBJECT' AND source_fingerprint REGEXP '^[0-9a-f]{64}$'
      THEN 1 ELSE 0 END=1
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE coop_settlement_cash_contribution (
  id BIGINT NOT NULL AUTO_INCREMENT,
  cash_close_id BIGINT NOT NULL,
  registration_id BIGINT NOT NULL,
  payer_registration_id BIGINT NOT NULL,
  parent_order_no VARCHAR(40) NULL,
  registration_no VARCHAR(32) NOT NULL,
  payer_order_no VARCHAR(32) NOT NULL,
  owner_type TINYINT NOT NULL,
  owner_id BIGINT NOT NULL,
  source_topic_id BIGINT NOT NULL,
  snapshot_payment_status TINYINT NULL,
  snapshot_registration_status TINYINT NULL,
  snapshot_verification_status TINYINT NULL,
  snapshot_payable_amount DECIMAL(15,2) NULL,
  sales_basis_amount DECIMAL(15,2) NOT NULL,
  registration_snapshot JSON NOT NULL,
  source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_g_cash_contribution_registration(registration_id),
  UNIQUE KEY uk_g_cash_contribution_close_registration(cash_close_id,registration_id),
  UNIQUE KEY uk_g_cash_contribution_payer_ref(cash_close_id,registration_id,registration_no),
  CONSTRAINT fk_g_cash_contribution_close FOREIGN KEY(cash_close_id,source_topic_id)
    REFERENCES coop_settlement_cash_close(id,topic_id),
  CONSTRAINT fk_g_cash_contribution_registration FOREIGN KEY(registration_id) REFERENCES cms_registration(id),
  CONSTRAINT fk_g_cash_contribution_payer FOREIGN KEY(cash_close_id,payer_registration_id,payer_order_no)
    REFERENCES coop_settlement_cash_contribution(cash_close_id,registration_id,registration_no),
  CONSTRAINT ck_g_cash_contribution CHECK (
    CASE WHEN registration_id>0 AND payer_registration_id>0 AND owner_type=1 AND owner_id=source_topic_id
      AND (parent_order_no IS NOT NULL OR
        (payer_registration_id=registration_id AND BINARY payer_order_no=BINARY registration_no))
      AND sales_basis_amount>=0
      AND sales_basis_amount=CASE WHEN snapshot_payment_status=2 AND snapshot_registration_status=2
            THEN snapshot_payable_amount ELSE 0 END
      AND CHAR_LENGTH(registration_no)>0 AND CHAR_LENGTH(payer_order_no)>0
      AND JSON_TYPE(registration_snapshot)='OBJECT' AND source_fingerprint REGEXP '^[0-9a-f]{64}$'
      THEN 1 ELSE 0 END=1
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE coop_settlement_cash_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  cash_close_id BIGINT NOT NULL,
  event_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_id BIGINT NOT NULL,
  source_registration_id BIGINT NOT NULL,
  source_amount DECIMAL(15,2) NOT NULL,
  source_snapshot JSON NOT NULL,
  source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_g_cash_event_source(cash_close_id,event_type,source_id),
  UNIQUE KEY uk_g_cash_event_id_close(id,cash_close_id),
  CONSTRAINT fk_g_cash_event_refund FOREIGN KEY(source_id) REFERENCES refund_application(id),
  CONSTRAINT fk_g_cash_event_contribution FOREIGN KEY(cash_close_id,source_registration_id)
    REFERENCES coop_settlement_cash_contribution(cash_close_id,registration_id),
  CONSTRAINT ck_g_cash_event CHECK (
    CASE WHEN BINARY event_type=BINARY 'REFUND_APPROVED' AND source_id>0
      AND source_registration_id>0 AND source_amount>=0 AND JSON_TYPE(source_snapshot)='OBJECT'
      AND source_fingerprint REGEXP '^[0-9a-f]{64}$' THEN 1 ELSE 0 END=1
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE coop_settlement
  MODIFY merchant_id BIGINT NULL COMMENT '真实会员受款人；仅显式ACCOUNTING平台对账行为空',
  ADD cash_head_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MEMBER',
  ADD cash_close_id BIGINT NULL,
  ADD cash_member_id BIGINT UNSIGNED GENERATED ALWAYS AS (
    CASE WHEN merchant_id>0 THEN merchant_id ELSE NULL END) STORED,
  ADD cash_initiator_member_id BIGINT UNSIGNED GENERATED ALWAYS AS (
    CASE WHEN line_type IN ('INITIATOR_GUARANTEE','INITIATOR_MARGIN') THEN merchant_id ELSE NULL END) STORED,
  ADD cash_global_line_type VARCHAR(24) GENERATED ALWAYS AS (
    CASE WHEN cash_close_id IS NOT NULL AND line_type IN ('PLATFORM','INITIATOR_GUARANTEE','INITIATOR_MARGIN')
      THEN line_type ELSE NULL END) STORED,
  ADD KEY idx_g_cash_head_member(cash_member_id),
  ADD KEY idx_g_cash_head_close(cash_close_id,topic_id),
  ADD KEY idx_g_cash_head_initiator(cash_close_id,cash_initiator_member_id),
  ADD UNIQUE KEY uk_g_cash_head_id_close(id,cash_close_id),
  ADD UNIQUE KEY uk_g_cash_global_head(cash_close_id,cash_global_line_type),
  ADD CONSTRAINT fk_g_cash_head_member FOREIGN KEY(cash_member_id) REFERENCES ums_member(id),
  ADD CONSTRAINT fk_g_cash_head_close FOREIGN KEY(cash_close_id,topic_id) REFERENCES coop_settlement_cash_close(id,topic_id),
  ADD CONSTRAINT fk_g_cash_head_initiator FOREIGN KEY(cash_close_id,cash_initiator_member_id)
    REFERENCES coop_settlement_cash_close(id,initiator_member_id),
  ADD CONSTRAINT ck_g_cash_head_payee CHECK (
    CASE WHEN BINARY cash_head_kind=BINARY 'ACCOUNTING' THEN
      CASE WHEN merchant_id IS NULL AND BINARY payee_type=BINARY 'platform'
        AND BINARY line_type IN (BINARY 'PLATFORM',BINARY 'DEPOSIT_DEDUCTION') AND status IN (1,2) THEN 1 ELSE 0 END
      WHEN BINARY cash_head_kind=BINARY 'MEMBER' THEN
        CASE WHEN merchant_id>0 AND (payee_type IS NULL OR payee_type<>'platform')
          AND (line_type IS NULL OR line_type NOT IN ('PLATFORM','DEPOSIT_DEDUCTION')) THEN 1 ELSE 0 END
      ELSE 0 END=1
  );

ALTER TABLE coop_settlement_adjustment
  ADD cash_close_id BIGINT NULL,
  ADD cash_event_id BIGINT NULL,
  ADD KEY idx_g_cash_adjustment_event(cash_event_id,cash_close_id),
  ADD KEY idx_g_cash_adjustment_head(settlement_id,cash_close_id),
  ADD UNIQUE KEY uk_g_cash_adjustment_event(cash_event_id,settlement_id,claim_id),
  ADD CONSTRAINT fk_g_cash_adjustment_event FOREIGN KEY(cash_event_id,cash_close_id)
    REFERENCES coop_settlement_cash_event(id,cash_close_id),
  ADD CONSTRAINT fk_g_cash_adjustment_head FOREIGN KEY(settlement_id,cash_close_id)
    REFERENCES coop_settlement(id,cash_close_id),
  ADD CONSTRAINT ck_g_cash_adjustment_link CHECK (
    CASE WHEN claim_id>0 AND
      ((cash_close_id IS NULL AND cash_event_id IS NULL)
       OR (cash_close_id IS NOT NULL AND cash_event_id IS NOT NULL)) THEN 1 ELSE 0 END=1
  );

-- readback: structural expansion only; claim_id NOT NULL and original unique keys retained.
-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name IN ('coop_settlement_cash_close','coop_settlement_cash_contribution','coop_settlement_cash_event'))=45
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND ((table_name='coop_settlement' AND column_name IN ('cash_head_kind','cash_close_id','cash_member_id','cash_initiator_member_id','cash_global_line_type'))
      OR (table_name='coop_settlement_adjustment' AND column_name IN ('cash_close_id','cash_event_id'))))=7
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='coop_settlement_adjustment' AND column_name='claim_id' AND is_nullable='NO')=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='coop_settlement' AND index_name='uk_settle_order' AND column_name='coop_order_id' AND non_unique=0)=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='coop_settlement_adjustment' AND index_name='uk_coop_adjustment_event' AND non_unique=0)=3
  AND (SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()
    AND constraint_name IN ('fk_g_cash_close_member','fk_g_cash_contribution_close','fk_g_cash_contribution_registration','fk_g_cash_contribution_payer','fk_g_cash_event_refund','fk_g_cash_event_contribution','fk_g_cash_head_member','fk_g_cash_head_close','fk_g_cash_head_initiator','fk_g_cash_adjustment_event','fk_g_cash_adjustment_head'))=11
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND constraint_type='CHECK' AND constraint_name IN ('ck_g_cash_close','ck_g_cash_contribution','ck_g_cash_event','ck_g_cash_head_payee','ck_g_cash_adjustment_link') AND enforced='YES')=5,
  1, JSON_EXTRACT('ASSERT_FAILED__g_cash_evidence_after_ddl','$')
) AS UNSIGNED) AS assertion_ok;
