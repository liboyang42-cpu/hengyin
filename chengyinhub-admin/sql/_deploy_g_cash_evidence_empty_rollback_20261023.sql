-- REVIEW/CONTROLLED-TEST ONLY. Never rollback populated evidence/accounting rows.
-- DDL auto-commits; this hard guard is before ANY rollback DDL. No DELETE/TRUNCATE or fabricated ID.
-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM coop_settlement_cash_event)=0
  AND (SELECT COUNT(*) FROM coop_settlement_cash_contribution)=0
  AND (SELECT COUNT(*) FROM coop_settlement_cash_close)=0
  AND (SELECT COUNT(*) FROM coop_settlement WHERE cash_close_id IS NOT NULL OR cash_head_kind<>'MEMBER' OR merchant_id IS NULL)=0
  AND (SELECT COUNT(*) FROM coop_settlement_adjustment WHERE cash_close_id IS NOT NULL OR cash_event_id IS NOT NULL)=0,
  1, JSON_EXTRACT('ASSERT_FAILED__g_cash_evidence_rollback_requires_empty','$')
) AS UNSIGNED) AS assertion_ok;
ALTER TABLE coop_settlement_adjustment
  DROP FOREIGN KEY fk_g_cash_adjustment_event,
  DROP FOREIGN KEY fk_g_cash_adjustment_head,
  DROP CHECK ck_g_cash_adjustment_link,
  DROP INDEX uk_g_cash_adjustment_event,
  DROP INDEX idx_g_cash_adjustment_event,
  DROP INDEX idx_g_cash_adjustment_head,
  DROP COLUMN cash_event_id, DROP COLUMN cash_close_id;
ALTER TABLE coop_settlement
  DROP FOREIGN KEY fk_g_cash_head_initiator,
  DROP FOREIGN KEY fk_g_cash_head_close,
  DROP FOREIGN KEY fk_g_cash_head_member,
  DROP CHECK ck_g_cash_head_payee,
  DROP INDEX uk_g_cash_global_head,
  DROP INDEX uk_g_cash_head_id_close,
  DROP INDEX idx_g_cash_head_member,
  DROP INDEX idx_g_cash_head_close,
  DROP INDEX idx_g_cash_head_initiator,
  DROP COLUMN cash_global_line_type,
  DROP COLUMN cash_initiator_member_id,
  DROP COLUMN cash_member_id,
  DROP COLUMN cash_close_id,
  DROP COLUMN cash_head_kind,
  MODIFY merchant_id BIGINT NOT NULL COMMENT '收款商家memberId(=coop_order.to_id)';
DROP TABLE coop_settlement_cash_event;
DROP TABLE coop_settlement_cash_contribution;
DROP TABLE coop_settlement_cash_close;
