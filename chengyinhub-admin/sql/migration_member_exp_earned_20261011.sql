-- 本周全部获得EXP：任务和直发共用获得明细，不用累计余额推算历史发放。
CREATE TABLE IF NOT EXISTS member_exp_earned (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  exp_value INT NOT NULL,
  reason TEXT NULL,
  earned_at DATETIME NOT NULL,
  mission_progress_id BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exp_earned_mission (mission_progress_id),
  KEY idx_exp_earned_week (earned_at, member_id),
  KEY idx_exp_earned_member (member_id, earned_at),
  CONSTRAINT chk_exp_earned_positive CHECK (exp_value > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经验实际获得明细';

-- 仅回填已有正向任务记录及其真实创建时间；历史直发未留明细，不能补造。
INSERT INTO member_exp_earned(member_id, exp_value, reason, earned_at, mission_progress_id)
SELECT member_id, reward_exp, mission_code, create_time, id
FROM growth_mission_progress
WHERE reward_exp > 0 AND create_time IS NOT NULL AND member_id > 0
ON DUPLICATE KEY UPDATE id = member_exp_earned.id;
