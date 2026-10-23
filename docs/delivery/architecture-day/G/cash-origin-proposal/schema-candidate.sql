-- REVIEW CANDIDATE ONLY / NOT EXECUTED / NOT A PRODUCTION MIGRATION.
-- Parent: ee71b74b629539e9a257c308491f8f9d94fe193e. Requires root schema approval.
-- MySQL >= 8.0.16; strict mode; run preflight.sql first in a separately approved environment.
-- No IF NOT EXISTS: shape drift is an error. DDL auto-commits; see README deployment checkpoints.

-- 1. Empty payee is ONLY a non-member platform accounting row. No fake member/claim ID.
ALTER TABLE coop_settlement
  MODIFY merchant_id BIGINT NULL COMMENT '真实会员受款人；仅平台对账行为空',
  ADD CONSTRAINT ck_coop_accounting_payee CHECK (
    CASE WHEN merchant_id IS NULL THEN
      CASE WHEN line_type IN ('PLATFORM','DEPOSIT_DEDUCTION')
                 AND payee_type='platform' AND status IN (1,2) THEN 1 ELSE 0 END
    ELSE
      CASE WHEN line_type IN ('PLATFORM','DEPOSIT_DEDUCTION') THEN 0
           WHEN merchant_id>0 AND (payee_type IS NULL OR payee_type<>'platform') THEN 1 ELSE 0 END
    END=1
  );

-- 2. Original close inputs, not a replacement parent for coop_settlement economic lines.
CREATE TABLE coop_settlement_cash_close (
  id BIGINT NOT NULL AUTO_INCREMENT,
  topic_id BIGINT NOT NULL,
  commercial_context_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  commercial_context_id BIGINT NOT NULL,
  platform_head_id BIGINT NOT NULL,
  initiator_member_id BIGINT NOT NULL,
  original_sales_basis DECIMAL(15,2) NOT NULL,
  original_contribution_count INT NOT NULL,
  platform_rate DECIMAL(10,6) NOT NULL,
  guarantee_base_rate DECIMAL(10,6) NOT NULL,
  guarantee_step_rate DECIMAL(10,6) NOT NULL,
  guarantee_max_merchant INT NOT NULL,
  original_verified_merchant_count INT NOT NULL,
  calculation_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  contract_snapshot JSON NOT NULL,
  source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  closed_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_coop_cash_close_topic(topic_id),
  UNIQUE KEY uk_coop_cash_close_platform(platform_head_id),
  CONSTRAINT fk_coop_cash_close_platform FOREIGN KEY(platform_head_id) REFERENCES coop_settlement(id),
  CONSTRAINT ck_coop_cash_close CHECK (
    commercial_context_type='TOPIC_EDITION' AND commercial_context_id=topic_id
    AND topic_id>0 AND initiator_member_id>0 AND original_sales_basis>0
    AND original_contribution_count>0 AND guarantee_max_merchant>=0
    AND original_verified_merchant_count>=0
    AND platform_rate BETWEEN 0 AND 1 AND guarantee_base_rate BETWEEN 0 AND 1
    AND guarantee_step_rate BETWEEN 0 AND 1
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. One original event per close/source; PENDING is durable intent, APPLIED is normalization only.
CREATE TABLE coop_settlement_cash_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  cash_close_id BIGINT NOT NULL,
  event_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_id BIGINT NOT NULL,
  source_registration_id BIGINT NOT NULL,
  source_amount DECIMAL(15,2) NOT NULL,
  source_snapshot JSON NOT NULL,
  source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  calculation_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  plan_snapshot JSON NULL,
  plan_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  expected_effect_count INT NULL,
  failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  next_attempt_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  applied_at DATETIME(6) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_coop_cash_event(cash_close_id,event_type,source_id),
  KEY idx_coop_cash_event_pending(state,next_attempt_at,cash_close_id,id),
  CONSTRAINT fk_coop_cash_event_close FOREIGN KEY(cash_close_id) REFERENCES coop_settlement_cash_close(id),
  CONSTRAINT ck_coop_cash_event CHECK (
    event_type IN ('REFUND_APPROVED','PAYMENT_CONFIRMED') AND source_id>0
    AND source_registration_id>0 AND source_amount>=0
    AND CASE WHEN state='APPLIED' THEN
      CASE WHEN calculation_version IS NOT NULL AND plan_snapshot IS NOT NULL
        AND plan_fingerprint IS NOT NULL AND expected_effect_count>=0 AND applied_at IS NOT NULL
        THEN 1 ELSE 0 END
      WHEN state IN ('PENDING','REQUIRES_REVIEW') THEN
        CASE WHEN applied_at IS NULL THEN 1 ELSE 0 END ELSE 0 END=1
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. All original paid contributions, including unverified/zero-payable siblings, remain immutable.
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
  basis_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sales_basis_amount DECIMAL(15,2) NOT NULL,
  registration_snapshot JSON NOT NULL,
  payment_evidence_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payment_evidence_id BIGINT NOT NULL,
  admission_event_id BIGINT NULL,
  source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_coop_cash_contribution(cash_close_id,registration_id),
  KEY idx_coop_cash_contribution_payer(payer_registration_id,cash_close_id),
  CONSTRAINT fk_coop_cash_contribution_close FOREIGN KEY(cash_close_id) REFERENCES coop_settlement_cash_close(id),
  CONSTRAINT fk_coop_cash_contribution_event FOREIGN KEY(admission_event_id) REFERENCES coop_settlement_cash_event(id),
  CONSTRAINT ck_coop_cash_contribution CHECK (
    registration_id>0 AND payer_registration_id>0 AND owner_id>0 AND source_topic_id>0
    AND sales_basis_amount>=0 AND payment_evidence_id>0
    AND payment_evidence_type IN ('REGISTRATION_PAID_STATE','VERIFIED_PAYMENT_FACT')
    AND (payment_evidence_type<>'REGISTRATION_PAID_STATE' OR payment_evidence_id=registration_id)
    AND CASE WHEN basis_kind='CLASSIC_SALES' THEN
      CASE WHEN owner_type=1 AND owner_id=source_topic_id THEN 1 ELSE 0 END
      WHEN basis_kind='ACTIVITY_OBSERVED' THEN
        CASE WHEN owner_type=2 AND admission_event_id IS NOT NULL THEN 1 ELSE 0 END ELSE 0 END=1
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. Keep existing original claims/details and old event uniqueness unchanged.
-- New CASH_HEAD effect is bound to the close+head, not fabricated as a merchant fulfillment claim.
ALTER TABLE coop_settlement_adjustment
  MODIFY claim_id BIGINT NULL,
  ADD cash_event_id BIGINT NULL,
  ADD effect_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD execution_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD parent_cash_adjustment_id BIGINT NULL,
  ADD unpaid_net_amount DECIMAL(15,2) NULL,
  ADD paid_flow_id BIGINT NULL,
  ADD balance_flow_id BIGINT NULL,
  ADD debt_id BIGINT NULL,
  ADD executed_debit_amount DECIMAL(15,2) NULL,
  ADD debt_amount DECIMAL(15,2) NULL,
  ADD UNIQUE KEY uk_coop_cash_effect(cash_event_id,settlement_id,effect_key),
  ADD UNIQUE KEY uk_coop_cash_effect_balance(balance_flow_id),
  ADD UNIQUE KEY uk_coop_cash_effect_debt(debt_id),
  ADD CONSTRAINT fk_coop_cash_effect_parent FOREIGN KEY(parent_cash_adjustment_id) REFERENCES coop_settlement_adjustment(id),
  ADD CONSTRAINT fk_coop_cash_effect_event FOREIGN KEY(cash_event_id) REFERENCES coop_settlement_cash_event(id),
  ADD CONSTRAINT fk_coop_cash_effect_paid FOREIGN KEY(paid_flow_id) REFERENCES ums_member_balance_detail(id),
  ADD CONSTRAINT fk_coop_cash_effect_balance FOREIGN KEY(balance_flow_id) REFERENCES ums_member_balance_detail(id),
  ADD CONSTRAINT fk_coop_cash_effect_debt FOREIGN KEY(debt_id) REFERENCES ums_member_settlement_debt(id),
  ADD CONSTRAINT ck_coop_cash_effect CHECK (
    CASE WHEN cash_event_id IS NULL THEN
      CASE WHEN claim_id IS NOT NULL AND effect_key IS NULL AND execution_kind IS NULL
        AND paid_flow_id IS NULL AND balance_flow_id IS NULL AND debt_id IS NULL
        AND parent_cash_adjustment_id IS NULL AND unpaid_net_amount IS NULL
        AND executed_debit_amount IS NULL AND debt_amount IS NULL THEN 1 ELSE 0 END
    ELSE
      CASE WHEN entry_kind='ADJUSTMENT' AND status='EXECUTED' AND amount<>0
        AND event_key=CONCAT('cash_event:',cash_event_id)
        AND CASE WHEN execution_kind='DELIVERY_NET' THEN
          CASE WHEN claim_id IS NULL AND parent_cash_adjustment_id IS NOT NULL
            AND effect_key=CONCAT('DELIVERY:',parent_cash_adjustment_id) THEN 1 ELSE 0 END
          ELSE CASE WHEN parent_cash_adjustment_id IS NULL
            AND ((claim_id IS NULL AND effect_key='CASH_HEAD')
              OR (claim_id IS NOT NULL AND effect_key=CONCAT('CLAIM:',claim_id))) THEN 1 ELSE 0 END END=1
        AND unpaid_net_amount>=0 AND executed_debit_amount>=0 AND debt_amount>=0
        AND CASE WHEN execution_kind='RECOVERY' THEN
          CASE WHEN amount<0 AND unpaid_net_amount+executed_debit_amount+debt_amount=-amount
            AND ((executed_debit_amount+debt_amount=0 AND paid_flow_id IS NULL)
              OR (executed_debit_amount+debt_amount>0 AND paid_flow_id IS NOT NULL))
            AND ((executed_debit_amount=0 AND balance_flow_id IS NULL)
              OR (executed_debit_amount>0 AND balance_flow_id IS NOT NULL))
            AND ((debt_amount=0 AND debt_id IS NULL) OR (debt_amount>0 AND debt_id IS NOT NULL))
            THEN 1 ELSE 0 END
          WHEN execution_kind IN ('ACCOUNTING','NET_PENDING_HEAD','SUPPLEMENTAL_PAYABLE','DELIVERY_NET') THEN
            CASE WHEN unpaid_net_amount=0 AND executed_debit_amount=0 AND debt_amount=0 AND paid_flow_id IS NULL
              AND balance_flow_id IS NULL AND debt_id IS NULL
              AND (execution_kind<>'SUPPLEMENTAL_PAYABLE' OR amount>0)
              AND (execution_kind<>'DELIVERY_NET' OR amount<0) THEN 1 ELSE 0 END
          ELSE 0 END=1
        THEN 1 ELSE 0 END
    END=1
  );

-- 6. Every original economic head links to one close. Positive post-paid delta gets a delivery head.
-- Existing uk_settle_order is retained; a delivery head has coop_order_id=NULL and claims are NOT reissued.
ALTER TABLE coop_settlement
  ADD cash_close_id BIGINT NULL,
  ADD source_cash_adjustment_id BIGINT NULL,
  ADD cash_global_line_type VARCHAR(24) GENERATED ALWAYS AS (
    CASE WHEN cash_close_id IS NOT NULL AND source_cash_adjustment_id IS NULL
           AND line_type IN ('PLATFORM','INITIATOR_GUARANTEE','INITIATOR_MARGIN')
      THEN line_type ELSE NULL END
  ) STORED,
  ADD UNIQUE KEY uk_coop_cash_global_head(cash_close_id,cash_global_line_type),
  ADD UNIQUE KEY uk_coop_cash_delivery(source_cash_adjustment_id),
  ADD KEY idx_coop_cash_close_head(cash_close_id,id),
  ADD CONSTRAINT fk_coop_cash_head_close FOREIGN KEY(cash_close_id) REFERENCES coop_settlement_cash_close(id),
  ADD CONSTRAINT fk_coop_cash_delivery_adjustment FOREIGN KEY(source_cash_adjustment_id) REFERENCES coop_settlement_adjustment(id),
  ADD CONSTRAINT ck_coop_cash_delivery CHECK (
    CASE WHEN source_cash_adjustment_id IS NULL THEN 1
      WHEN cash_close_id IS NOT NULL AND line_type='ADJUSTMENT_PAYOUT'
        AND coop_order_id IS NULL AND merchant_id>0 AND payee_type<>'platform' AND amount>0
        THEN 1 ELSE 0 END=1
  );
