-- REVIEW WITNESSES ONLY. NOT EXECUTED. NOT A MIGRATION OR APPROVED FINANCIAL ALGORITHM.
-- Root/G must install the exact canonical base+migrations+approved candidate in an isolated MySQL IT.
-- The IT supplies valid independent fixtures and variables, never production IDs:
-- @close_a/topic_a/event_a belong to A; @close_b/head_b/supplement_b belong to B.
-- event_a is a valid planned PENDING REFUND_APPROVED event. head_b is a real PLATFORM head of B.
-- supplement_b is a valid positive SUPPLEMENTAL_PAYABLE effect whose original head belongs to B.
-- @missing_head is positively known absent; @member_a is an actual fixture member, not a platform stand-in.
-- All UNIQUE target tuples and source_cash_adjustment_id below must initially be unused.
-- Every case is independent and always rolled back by the test, including expected-error branches.

-- S02a EXPECT REJECTION: an effect must not reference a nonexistent target head.
-- Static reading of G5e280 predicts no FK/CHECK rejects this row; MySQL NOT_RUN.
START TRANSACTION;
INSERT INTO coop_settlement_adjustment
 (settlement_id,claim_id,entry_kind,event_key,amount,status,create_time,
  cash_event_id,effect_key,execution_kind,unpaid_net_amount,executed_debit_amount,debt_amount)
VALUES
 (@missing_head,NULL,'ADJUSTMENT',CONCAT('cash_event:',@event_a),-0.01,'EXECUTED',NOW(6),
  @event_a,'CASH_HEAD','ACCOUNTING',0,0,0);
ROLLBACK;

-- S02b EXPECT REJECTION: a real head from B cannot be adjusted by the event from A.
START TRANSACTION;
INSERT INTO coop_settlement_adjustment
 (settlement_id,claim_id,entry_kind,event_key,amount,status,create_time,
  cash_event_id,effect_key,execution_kind,unpaid_net_amount,executed_debit_amount,debt_amount)
VALUES
 (@head_b,NULL,'ADJUSTMENT',CONCAT('cash_event:',@event_a),-0.01,'EXECUTED',NOW(6),
  @event_a,'CASH_HEAD','ACCOUNTING',0,0,0);
ROLLBACK;

-- S02c EXPECT REJECTION: a delivery head of A cannot use the supplemental effect from B.
-- Its standalone delivery/payee shape is valid; this probes the missing cross-row close relationship.
START TRANSACTION;
INSERT INTO coop_settlement
 (topic_id,coop_order_id,merchant_id,amount,status,del_flag,line_type,payee_type,
  cash_close_id,source_cash_adjustment_id)
VALUES
 (@topic_a,NULL,@member_a,0.01,0,0,'ADJUSTMENT_PAYOUT','initiator',@close_a,@supplement_b);
ROLLBACK;

-- S01 prerequisite: @original_head_a is a genuinely paid INITIATOR_GUARANTEE head,
-- and @original_paid_flow its true payout proof. No fabricated merchant claim is used.
-- The chosen recovery source is valid; all balances/debt work must use the newly allocated effect.id.
-- Current CHECK EXPECTS REJECTION of this attempted ID-reservation insert because its debit receipt is absent.
-- A correct implementation must supply a defined ID allocation / PLANNED->EXECUTED transaction protocol,
-- rather than weakening EXECUTED to accept missing proof or using another business ID as effect.id.
START TRANSACTION;
INSERT INTO coop_settlement_adjustment
 (settlement_id,claim_id,entry_kind,event_key,amount,status,create_time,
  cash_event_id,effect_key,execution_kind,unpaid_net_amount,executed_debit_amount,debt_amount,paid_flow_id)
VALUES
 (@original_head_a,NULL,'ADJUSTMENT',CONCAT('cash_event:',@event_a),-0.01,'EXECUTED',NOW(6),
  @event_a,'CASH_HEAD','RECOVERY',0,0.01,0,@original_paid_flow);
ROLLBACK;

-- The same candidate also rejects a PENDING cash effect; it has no supported staged-identity row today.
START TRANSACTION;
INSERT INTO coop_settlement_adjustment
 (settlement_id,claim_id,entry_kind,event_key,amount,status,create_time,
  cash_event_id,effect_key,execution_kind,unpaid_net_amount,executed_debit_amount,debt_amount)
VALUES
 (@original_head_a,NULL,'ADJUSTMENT',CONCAT('cash_event:',@event_a),-0.01,'PENDING',NOW(6),
  @event_a,'CASH_HEAD','RECOVERY',0,0,0);
ROLLBACK;
