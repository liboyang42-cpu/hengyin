-- ================= migration_20261130_payout_bind_match.sql =================
-- W7 x-money-on-readiness (MERCHANT-8): number matching for the payout-binding QR code.
-- The mini-program shows the scanner a two-digit number; the app owner must pick the same number before the WeChat
-- is bound. A wrong pick cancels the code. Money stays off: every binding step still needs its money switch area.
--
-- ROLLBACK (old jar ignores the column, so it can stay):
--   ALTER TABLE cy_payout_bind_code DROP COLUMN match_number;
--
-- Runs BEFORE the new jar (the new mapper selects match_number). Idempotent: guarded by information_schema.columns.

SET @pbm_col := (
    SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='cy_payout_bind_code' AND column_name='match_number');
SET @pbm_sql := IF(@pbm_col=0,
    'ALTER TABLE cy_payout_bind_code ADD COLUMN match_number TINYINT NULL COMMENT ''MERCHANT-8: number shown to the scanner (10-99); the app owner must pick it'' AFTER claimed_openid',
    'SELECT 1');
PREPARE pbm_stmt FROM @pbm_sql;
EXECUTE pbm_stmt;
DEALLOCATE PREPARE pbm_stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='cy_payout_bind_code' AND column_name='match_number')=1 AS payout_bind_match_ready;
