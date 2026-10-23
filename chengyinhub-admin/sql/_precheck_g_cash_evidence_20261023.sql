-- G cash evidence expand: SELECT-only precheck, not executed in this environment.
SELECT CASE WHEN
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
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
       AND constraint_name LIKE 'fk_g_cash_%')=0
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
       AND table_name='coop_settlement' AND index_name='uk_settle_order' AND non_unique=0 AND column_name='coop_order_id')=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
       AND table_name='coop_settlement_adjustment' AND index_name='uk_coop_adjustment_event' AND non_unique=0)=3
  AND (SELECT COUNT(*) FROM coop_settlement h LEFT JOIN ums_member m ON m.id=h.merchant_id
       WHERE h.merchant_id IS NULL OR h.merchant_id<=0 OR m.id IS NULL
          OR h.payee_type='platform' OR h.line_type IN ('PLATFORM','DEPOSIT_DEDUCTION'))=0
  AND (SELECT COUNT(*) FROM coop_settlement_adjustment a LEFT JOIN coop_settlement h ON h.id=a.settlement_id
       WHERE a.claim_id IS NULL OR a.claim_id<=0 OR h.id IS NULL)=0
  THEN 0 ELSE 1 END AS blocker_count;
