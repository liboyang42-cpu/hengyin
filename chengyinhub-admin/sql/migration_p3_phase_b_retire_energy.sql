-- P3 Phase B 并池清理:退役「能量」与「探索分」
-- 价值模型统一为「经验 + 积分」;积分走 UmsMember.point + UmsMemberPointsDetail 统一账本(IPointsAwardService)。
-- 成长系统只保留经验/等级。本脚本删除已退役的列,可重复执行(列不存在则跳过)。

-- member_growth.energy_value
SET @sql := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'member_growth' AND column_name = 'energy_value'),
  'ALTER TABLE member_growth DROP COLUMN energy_value', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- member_growth.city_score
SET @sql := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'member_growth' AND column_name = 'city_score'),
  'ALTER TABLE member_growth DROP COLUMN city_score', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- growth_mission.energy_reward
SET @sql := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'growth_mission' AND column_name = 'energy_reward'),
  'ALTER TABLE growth_mission DROP COLUMN energy_reward', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- growth_mission_progress.reward_energy
SET @sql := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'growth_mission_progress' AND column_name = 'reward_energy'),
  'ALTER TABLE growth_mission_progress DROP COLUMN reward_energy', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ip_quest.reward_energy(IP世界任务也只保留经验)
SET @sql := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'ip_quest' AND column_name = 'reward_energy'),
  'ALTER TABLE ip_quest DROP COLUMN reward_energy', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
