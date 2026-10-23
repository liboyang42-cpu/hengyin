-- REVIEW ONLY. Read-only preflight; NOT EXECUTED. No schema name/credentials embedded.
-- Run only via separately approved CI/VPS or approved production read procedure.
SELECT DATABASE() AS inspected_schema, VERSION() AS mysql_version, @@sql_mode AS sql_mode;
SELECT table_name,engine FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name IN
 ('coop_settlement','coop_settlement_detail','coop_settlement_adjustment','settlement_claim',
  'cms_registration','refund_application','ums_member_balance_detail','ums_member_settlement_debt',
  'coop_settlement_cash_close','coop_settlement_cash_contribution','coop_settlement_cash_event');
SELECT table_name,column_name,column_type,is_nullable,column_default,extra
 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name IN ('coop_settlement','coop_settlement_adjustment','ums_member_balance_detail','ums_member_settlement_debt')
 ORDER BY table_name,ordinal_position;
SELECT table_name,index_name,non_unique,seq_in_index,column_name
 FROM information_schema.statistics WHERE table_schema=DATABASE()
 AND table_name IN ('coop_settlement','coop_settlement_detail','coop_settlement_adjustment','settlement_claim')
 ORDER BY table_name,index_name,seq_in_index;
SELECT table_name,constraint_name,constraint_type FROM information_schema.table_constraints
 WHERE table_schema=DATABASE() AND table_name IN ('coop_settlement','coop_settlement_adjustment');

-- Every returned row blocks the proposed payee CHECK. Never repair by fake ID/deleting a ledger.
SELECT id,topic_id,line_type,payee_type,merchant_id,status FROM coop_settlement
 WHERE CASE WHEN merchant_id IS NULL THEN
   CASE WHEN line_type IN ('PLATFORM','DEPOSIT_DEDUCTION')
     AND payee_type='platform' AND status IN (1,2) THEN 1 ELSE 0 END
 ELSE CASE WHEN line_type IN ('PLATFORM','DEPOSIT_DEDUCTION') THEN 0
   WHEN merchant_id>0 AND (payee_type IS NULL OR payee_type<>'platform') THEN 1 ELSE 0 END END<>1
 ORDER BY id;
SELECT h.id,h.topic_id,h.merchant_id,h.line_type FROM coop_settlement h
 LEFT JOIN ums_member m ON m.id=h.merchant_id
 WHERE h.merchant_id IS NOT NULL AND m.id IS NULL ORDER BY h.id;
SELECT topic_id,line_type,COUNT(*) AS head_count FROM coop_settlement
 WHERE line_type IN ('PLATFORM','INITIATOR_GUARANTEE','INITIATOR_MARGIN')
 GROUP BY topic_id,line_type HAVING COUNT(*)>1;

-- Existing origin/accounting facts needing individual evidence before any legacy attribution.
SELECT h.id,h.topic_id,h.amount,COUNT(d.id) AS detail_count,COALESCE(SUM(d.origin_amount),0) AS detail_amount
 FROM coop_settlement h LEFT JOIN coop_settlement_detail d ON d.settlement_id=h.id
 WHERE h.coop_order_id IS NOT NULL GROUP BY h.id,h.topic_id,h.amount
 HAVING COUNT(d.id)=0 OR h.amount<>COALESCE(SUM(d.origin_amount),0);
SELECT d.id,d.settlement_id,d.claim_id,d.registration_id FROM coop_settlement_detail d
 LEFT JOIN settlement_claim c ON c.id=d.claim_id
 LEFT JOIN coop_settlement h ON h.id=d.settlement_id
 WHERE c.id IS NULL OR h.id IS NULL OR c.fulfillment_type<>'REGISTRATION' OR c.fulfillment_id<>d.registration_id;
SELECT event_id,member_id,COUNT(*) AS payout_flow_count,SUM(change_balance) AS credited
 FROM ums_member_balance_detail
 WHERE event_type=5 AND change_reason LIKE 'Coop结算打款 - 主题%'
 GROUP BY event_id,member_id HAVING COUNT(*)>1;
SELECT r.id,r.owner_type,r.owner_id,r.payable_amount,SUM(f.refund_amount) AS approved_refund
 FROM cms_registration r JOIN refund_application f
 ON f.source_type='registration' AND f.source_id=r.id
 WHERE f.del_flag=0 AND f.status=1
 GROUP BY r.id,r.owner_type,r.owner_id,r.payable_amount
 HAVING SUM(f.refund_amount)>0 AND SUM(f.refund_amount)<r.payable_amount;

-- Expected old rows: claim-bound only; do not backfill execution/debit/debt proof from EXECUTED status.
SELECT id,settlement_id,claim_id,event_key,amount,status FROM coop_settlement_adjustment ORDER BY id;
-- STOP if any candidate table/column/constraint already exists with a different shape.
-- SHOW CREATE TABLE / CHECK definitions and actual EXPLAIN/lock behavior are also required in approved CI.
