-- Preserve original earned amounts and dates; link only new completion awards to their real ledger row.
SET @exp_reversal_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'member_exp_earned' AND column_name = 'revoked_exp');
SET @exp_reversal_ddl = IF(@exp_reversal_exists = 0,
    'ALTER TABLE member_exp_earned ADD COLUMN revoked_exp INT NOT NULL DEFAULT 0, ADD CONSTRAINT chk_exp_earned_revoked CHECK (revoked_exp >= 0 AND revoked_exp <= exp_value)', 'SELECT 1');
PREPARE exp_reversal_stmt FROM @exp_reversal_ddl;
EXECUTE exp_reversal_stmt;
DEALLOCATE PREPARE exp_reversal_stmt;

SET @completion_link_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'topic_completion_xp_award' AND column_name = 'earned_exp_id');
SET @completion_link_ddl = IF(@completion_link_exists = 0,
    'ALTER TABLE topic_completion_xp_award ADD COLUMN earned_exp_id BIGINT NULL, ADD UNIQUE KEY uk_completion_earned_exp (earned_exp_id)', 'SELECT 1');
PREPARE completion_link_stmt FROM @completion_link_ddl;
EXECUTE completion_link_stmt;
DEALLOCATE PREPARE completion_link_stmt;

-- Existing task reversals carry the original mission progress ID and the actual reversed amount.
UPDATE member_exp_earned e
JOIN (SELECT member_id, biz_id, SUM(-reward_exp) AS reversed_exp
      FROM growth_mission_progress WHERE biz_type = 'registration_clawback' AND reward_exp < 0
      GROUP BY member_id, biz_id) r
  ON r.member_id = e.member_id AND r.biz_id = e.mission_progress_id
SET e.revoked_exp = GREATEST(e.revoked_exp, LEAST(e.exp_value, r.reversed_exp))
WHERE r.reversed_exp > 0 AND e.revoked_exp < LEAST(e.exp_value, r.reversed_exp);

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'member_exp_earned' AND column_name = 'revoked_exp'
  AND data_type = 'int' AND is_nullable = 'NO' AND column_default = '0';
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'topic_completion_xp_award' AND column_name = 'earned_exp_id'
  AND data_type = 'bigint' AND is_nullable = 'YES';
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.table_constraints t
JOIN information_schema.check_constraints c ON c.constraint_schema = t.constraint_schema
 AND c.constraint_name = t.constraint_name
WHERE t.constraint_schema = DATABASE() AND t.table_name = 'member_exp_earned'
  AND t.constraint_name = 'chk_exp_earned_revoked' AND t.constraint_type = 'CHECK' AND t.enforced = 'YES'
  AND REPLACE(REPLACE(REPLACE(REPLACE(c.check_clause, '`', ''), ' ', ''), '(', ''), ')', '')
      = 'revoked_exp>=0andrevoked_exp<=exp_value';
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AND MIN(non_unique) = 0 AND MIN(seq_in_index) = 1
  AND MIN(column_name) = 'earned_exp_id'
FROM information_schema.statistics WHERE table_schema = DATABASE()
  AND table_name = 'topic_completion_xp_award' AND index_name = 'uk_completion_earned_exp';
