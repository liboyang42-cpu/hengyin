-- migration_20261115_daily_loop.sql  (P7 daily-loop: GAM-04 / GRO-06 / GRO-07)
--
-- 回滚说明(rollback):
--   ALTER TABLE player_growth DROP COLUMN streak_freezes, DROP COLUMN streak_freezes_used;
--   ALTER TABLE growth_mission DROP COLUMN target_count;
--   DELETE FROM growth_badge WHERE badge_code IN ('STREAK_14','STREAK_30');
--   DELETE FROM growth_mission WHERE mission_code IN ('DAILY_WALK','DAILY_REVEAL','DAILY_PLAY','DAILY_SHOP');
--   DELETE FROM sys_config WHERE config_key IN ('growth.streak.walkMinMeters','growth.streak.walkMinCells',
--                                              'growth.streak.freezeEveryDays','growth.streak.freezeMax');
--   (回滚列之前必须先回滚 jar:新 jar 的 PlayerGrowthMapper / GrowthMissionMapper 会读这些列。)
--
-- 【上线顺序】本文件必须先于 jar 执行:新 jar 的 select 带 streak_freezes / target_count 列。
-- 幂等:列用 information_schema 守卫;种子用 INSERT IGNORE / WHERE NOT EXISTS,不覆盖运营已改的值。
--
-- 奖励口径:四条每日任务的 exp_reward 种子为 0 —— 本迁移不新增任何积分或经验发放;
-- 运营在后台「成长任务」里按现有配置填写经验值后才会发。

-- ① 补签卡:player_growth.streak_freezes(手里的卡)/ streak_freezes_used(累计自动用掉)
SET @sql := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'player_growth' AND column_name = 'streak_freezes'),
  'SELECT 1',
  'ALTER TABLE player_growth ADD COLUMN streak_freezes INT NOT NULL DEFAULT 0 COMMENT ''P7 手里的补签卡'' AFTER last_checkin_date');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'player_growth' AND column_name = 'streak_freezes_used'),
  'SELECT 1',
  'ALTER TABLE player_growth ADD COLUMN streak_freezes_used INT NOT NULL DEFAULT 0 COMMENT ''P7 累计自动用掉的补签卡'' AFTER streak_freezes');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ② 每日任务目标次数:growth_mission.target_count(普通任务 = 1)
SET @sql := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'growth_mission' AND column_name = 'target_count'),
  'SELECT 1',
  'ALTER TABLE growth_mission ADD COLUMN target_count INT NOT NULL DEFAULT 1 COMMENT ''P7 每日任务目标次数'' AFTER daily_limit');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ③ 连续徽章阶梯补齐 14 / 30 天(3 / 7 天见 migration_p4_badge_def_engine.sql)
INSERT INTO growth_badge (badge_code, badge_name, icon_url, asset_type, category, trigger_event, unlock_rule_json, rule_version, status, sort_no, create_time)
VALUES
 ('STREAK_14','两周不断','','GROWTH','EXPLORE','active_day','{"aggregate":"distinct","aggregate_field":"active_day","threshold":14,"window":{"type":"natural_day_streak"}}',1,'0',7,now()),
 ('STREAK_30','一月常在','','GROWTH','EXPLORE','active_day','{"aggregate":"distinct","aggregate_field":"active_day","threshold":30,"window":{"type":"natural_day_streak"}}',1,'0',8,now())
ON DUPLICATE KEY UPDATE
 asset_type=VALUES(asset_type), category=VALUES(category), trigger_event=VALUES(trigger_event),
 unlock_rule_json=VALUES(unlock_rule_json), rule_version=VALUES(rule_version);

-- ④ 四条自动记录的每日任务(trigger_event = daily_*;进度从真实事实表数,见 DailyLoopMapper.xml)
INSERT IGNORE INTO growth_mission (mission_code, mission_name, mission_desc, trigger_event, exp_reward, daily_limit, target_count, status, sort_no, create_time)
VALUES
 ('DAILY_WALK',   '走一走',   '完成一次漫游(达到里程或点亮格子的门槛)', 'daily_walk',   0, 1, 1,  '0', 1, now()),
 ('DAILY_REVEAL', '点亮迷雾', '今天点亮 20 个新格子',                   'daily_reveal', 0, 1, 20, '0', 2, now()),
 ('DAILY_PLAY',   '玩一关',   '在任意地点完成一个游戏节点',             'daily_play',   0, 1, 1,  '0', 3, now()),
 ('DAILY_SHOP',   '逛一家店', '漫游时到店打个卡',                       'daily_shop',   0, 1, 1,  '0', 4, now());

-- ⑤ 配置(sys_config,数值越界时代码回落默认值)
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '连续天数-漫游最短里程(米)', 'growth.streak.walkMinMeters', '500', 'Y', 'admin', NOW(),
       '一次漫游结算里程达到这个值就算今天走过;0=不看里程'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.streak.walkMinMeters');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '连续天数-漫游最少新格子', 'growth.streak.walkMinCells', '10', 'Y', 'admin', NOW(),
       '一次漫游新点亮格子达到这个数就算今天走过(与里程任一达标即可);0=不看格子'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.streak.walkMinCells');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '连续天数-每几天得一张补签卡', 'growth.streak.freezeEveryDays', '7', 'Y', 'admin', NOW(),
       '连续天数每满这个数得 1 张补签卡;0=不发卡'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.streak.freezeEveryDays');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '连续天数-补签卡上限', 'growth.streak.freezeMax', '2', 'Y', 'admin', NOW(),
       '最多攒几张补签卡(代码封顶 5);漏一天自动用一张'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.streak.freezeMax');

-- 上线后校验:
-- SELECT streak_freezes, streak_freezes_used FROM player_growth LIMIT 1;
-- SELECT mission_code, trigger_event, target_count, exp_reward FROM growth_mission WHERE trigger_event LIKE 'daily\_%';
-- SELECT badge_code FROM growth_badge WHERE badge_code LIKE 'STREAK\_%';
