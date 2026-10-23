-- CONDITIONAL REVIEW ONLY / NOT EXECUTED.
-- Only needed if user chooses original-order frozen splits (root question remains unanswered).
-- Not part of the shared three-table core; do not apply merely because this file exists.
-- Amount allocation/rounding is NOT specified/approved by this DDL.
CREATE TABLE coop_settlement_cash_allocation (
  contribution_id BIGINT NOT NULL,
  settlement_id BIGINT NOT NULL,
  origin_amount DECIMAL(15,2) NOT NULL,
  source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(contribution_id,settlement_id),
  KEY idx_coop_cash_allocation_head(settlement_id,contribution_id),
  CONSTRAINT fk_coop_cash_allocation_source FOREIGN KEY(contribution_id) REFERENCES coop_settlement_cash_contribution(id),
  CONSTRAINT fk_coop_cash_allocation_head FOREIGN KEY(settlement_id) REFERENCES coop_settlement(id),
  CONSTRAINT ck_coop_cash_allocation_amount CHECK (origin_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Application must prove contribution.close_id=head.cash_close_id and original (not delivery) head.
-- Only PLATFORM/INITIATOR_GUARANTEE/INITIATOR_MARGIN; merchant stays in original claim/detail.
-- Per-head allocation SUM must equal original head.amount; do not fill missing rows as zero.
