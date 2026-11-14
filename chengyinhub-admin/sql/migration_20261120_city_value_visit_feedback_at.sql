-- be-gap-merchant R3：city_value_visit 加 feedback_at = 玩家在一次到访里首次明确选择（SKIP/EXIT/SERVICE_RATING）的时间。
-- CITY 商家价值报告的 timeToFirstEffectiveChoice = feedback_at - entered_at；旧行为 NULL，不回填（报告只计有值的到访）。
-- 只加一列可空列，按 information_schema 判断，可重复执行。不涉及资金、库存、结算或支付。
SET @cy_has_feedback_at := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'city_value_visit' AND column_name = 'feedback_at');
SET @cy_sql := IF(@cy_has_feedback_at = 0,
  'ALTER TABLE city_value_visit ADD COLUMN feedback_at datetime(3) DEFAULT NULL COMMENT ''first explicit choice (SKIP/EXIT/SERVICE_RATING) time''',
  'SELECT 1');
PREPARE cy_stmt FROM @cy_sql;
EXECUTE cy_stmt;
DEALLOCATE PREPARE cy_stmt;
