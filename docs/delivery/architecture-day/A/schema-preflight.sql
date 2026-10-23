-- READ ONLY review checklist; NOT executed by task A. Use only an approved environment.
SELECT VERSION() AS mysql_version, DATABASE() AS selected_database;
SELECT table_name, engine FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('member_cash_withdraw', 'member_cash_withdraw_fund_fact', 'ums_member');
SELECT table_name, column_name, column_type, is_nullable
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND ((table_name = 'member_cash_withdraw' AND column_name IN
        ('id','member_id','amount','out_batch_no','out_detail_no','balance_deduct_id','balance_refund_id','frozen','manual_verifier'))
        OR (table_name = 'ums_member' AND column_name IN ('id','balance')))
 ORDER BY table_name, ordinal_position;
SELECT status, COUNT(*) AS row_count,
       SUM(balance_deduct_id IS NULL) AS no_debit_pointer,
       SUM(balance_refund_id IS NOT NULL) AS existing_refund_pointer
  FROM member_cash_withdraw GROUP BY status;
SELECT trigger_name, event_manipulation, action_timing
  FROM information_schema.triggers
 WHERE trigger_schema = DATABASE() AND event_object_table = 'member_cash_withdraw_fund_fact';
-- Reviewer separately verifies CREATE TABLE/CREATE TRIGGER privileges and application INSERT/SELECT
-- privileges. Do not copy grants, secret values or production addresses into this public repository.
