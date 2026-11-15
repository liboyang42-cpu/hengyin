-- R11 growth-loops (GRO-09 召回, GRO-13 邀请奖励, GRO-05 小程序↔App, GRO-08 CITY 通知口)
--
-- 【回滚】(先回滚 jar,再执行)
--   DELETE FROM sys_job WHERE invoke_target = 'growthWinbackTask.run()';
--   DELETE FROM sys_config WHERE config_key LIKE 'growth.winback.%' OR config_key LIKE 'growth.invite.%' OR config_key LIKE 'growth.app.%';
--   DELETE FROM growth_badge WHERE badge_code IN ('INVITE_1','INVITE_3','INVITE_10');
--   DROP TABLE IF EXISTS growth_winback_log, growth_winback_state, growth_invite_device, growth_invite_reward;
--   (player_badge 里已发的 INVITE_* 徽章是玩家资产,回滚时保留,不要删。)
--
-- 【上线顺序】先跑本文件再上 jar(新 jar 的召回任务与漫游结算读这些表;表不在时召回整轮报错跳过、邀请奖励只记日志,结算不受影响)。
-- 【幂等】CREATE TABLE IF NOT EXISTS;种子用 INSERT ... WHERE NOT EXISTS / ON DUPLICATE KEY,不覆盖运营已改的值。
-- 【安全默认】召回 growth.winback.mode = OFF:任务已登记为运行,但 OFF 时整轮空跑(不扫描、不发送)。
--   业主拍板后改成 SHADOW(只算、写 growth_winback_log,不发)或 ON(发 APNs)。
-- 【奖励口径】邀请奖励只发徽章(非现金、非积分)。不改 cms_points_setting(id 6 仍为 2026-11-04 关闭状态)。

CREATE TABLE IF NOT EXISTS growth_winback_state (
  member_id          BIGINT       NOT NULL,
  opted_out          TINYINT      NOT NULL DEFAULT 0  COMMENT '1 = 玩家关掉了召回提醒',
  opted_out_at       DATETIME     DEFAULT NULL,
  last_sent_at       DATETIME     DEFAULT NULL        COMMENT '最近一次被设备接受的召回',
  last_trigger       VARCHAR(24)  DEFAULT NULL        COMMENT 'STREAK_AT_RISK / CLUB_EVENT / NEW_PLACES',
  last_ref           VARCHAR(64)  DEFAULT NULL,
  awaiting_return    TINYINT      NOT NULL DEFAULT 0  COMMENT '1 = 发出后还在等玩家回来',
  ignored_count      TINYINT      NOT NULL DEFAULT 0  COMMENT '连续没回应次数;到 2 停发,玩家回来清零',
  sent_total         INT          NOT NULL DEFAULT 0,
  last_evaluated_at  DATETIME     DEFAULT NULL,
  update_time        DATETIME     DEFAULT NULL,
  PRIMARY KEY (member_id),
  KEY idx_sent (last_sent_at),
  KEY idx_evaluated (last_evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R11 召回:每位玩家的频率、没回应与退订状态';

CREATE TABLE IF NOT EXISTS growth_winback_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  member_id     BIGINT       NOT NULL,
  trigger_code  VARCHAR(24)  NOT NULL,
  ref           VARCHAR(64)  DEFAULT NULL,
  outcome       VARCHAR(16)  NOT NULL COMMENT 'SENT / NOT_DELIVERED / SHADOW',
  devices       INT          NOT NULL DEFAULT 0,
  create_time   DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_member_time (member_id, create_time),
  KEY idx_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R11 召回:每次发送/影子判定的记录';

CREATE TABLE IF NOT EXISTS growth_invite_reward (
  invitee_member_id   BIGINT       NOT NULL,
  inviter_member_id   BIGINT       NOT NULL,
  status              VARCHAR(12)  NOT NULL COMMENT 'GRANTED / REFUSED',
  reason              VARCHAR(32)  DEFAULT NULL COMMENT 'REFUSED 的原因:DUPLICATE_PHONE / DUPLICATE_DEVICE / SAME_PHONE / INVITER_MONTHLY_CAP / INVITER_INACTIVE',
  roam_session_id     BIGINT       DEFAULT NULL COMMENT '触发奖励的那次漫游',
  granted_phone_hash  CHAR(64)     DEFAULT NULL COMMENT 'SHA-256(手机号),只在 GRANTED 行写,唯一',
  create_time         DATETIME     NOT NULL,
  PRIMARY KEY (invitee_member_id),
  UNIQUE KEY uk_granted_phone (granted_phone_hash),
  KEY idx_inviter_time (inviter_member_id, status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R11 邀请奖励账本:每位被邀请人一行';

CREATE TABLE IF NOT EXISTS growth_invite_device (
  device_hash        CHAR(64)  NOT NULL COMMENT 'SHA-256(推送 token)',
  invitee_member_id  BIGINT    NOT NULL,
  create_time        DATETIME  NOT NULL,
  PRIMARY KEY (device_hash),
  KEY idx_invitee (invitee_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R11 邀请奖励:已得奖设备(同一台设备只算一次)';

-- 邀请徽章阶梯(阈值 = 累计成功邀请人数)
INSERT INTO growth_badge (badge_code, badge_name, icon_url, asset_type, category, trigger_event, unlock_rule_json, rule_version, status, sort_no, create_time)
VALUES
 ('INVITE_1', '带朋友上路', '', 'GROWTH', 'CONNECT', 'invite_reward', '{"aggregate":"count","aggregate_field":"invite_reward","threshold":1}', 1, '0', 20, now()),
 ('INVITE_3', '三人成行',   '', 'GROWTH', 'CONNECT', 'invite_reward', '{"aggregate":"count","aggregate_field":"invite_reward","threshold":3}', 1, '0', 21, now()),
 ('INVITE_10','城里向导',   '', 'GROWTH', 'CONNECT', 'invite_reward', '{"aggregate":"count","aggregate_field":"invite_reward","threshold":10}', 1, '0', 22, now())
ON DUPLICATE KEY UPDATE
 asset_type=VALUES(asset_type), category=VALUES(category), trigger_event=VALUES(trigger_event),
 unlock_rule_json=VALUES(unlock_rule_json), rule_version=VALUES(rule_version);

-- 配置
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '召回-模式', 'growth.winback.mode', 'OFF', 'Y', 'admin', NOW(), 'OFF 不扫描不发 / SHADOW 只算并写 growth_winback_log / ON 发 APNs(需业主拍板)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.winback.mode');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '召回-最短间隔(天)', 'growth.winback.minGapDays', '7', 'Y', 'admin', NOW(), '两条召回之间至少隔几天;代码下限 7'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.winback.minGapDays');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '召回-没回应上限', 'growth.winback.maxIgnored', '2', 'Y', 'admin', NOW(), '连续几次没回应后停发;代码上限 2'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.winback.maxIgnored');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '召回-流失天数', 'growth.winback.lapsedDays', '7', 'Y', 'admin', NOW(), '多少天没来算流失(新地点/附近活动提醒只发给流失玩家)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.winback.lapsedDays');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '召回-免打扰开始', 'growth.winback.quietStart', '21:00', 'Y', 'admin', NOW(), 'HH:mm,时区见 growth.winback.timezone'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.winback.quietStart');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '召回-免打扰结束', 'growth.winback.quietEnd', '09:00', 'Y', 'admin', NOW(), 'HH:mm'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.winback.quietEnd');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '召回-时区', 'growth.winback.timezone', 'Asia/Shanghai', 'Y', 'admin', NOW(), 'IANA 时区'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.winback.timezone');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '召回-连续天数提醒起始钟点', 'growth.winback.streakFromHour', '18', 'Y', 'admin', NOW(), '当地这个钟点之后才提醒“连续天数快断了”'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.winback.streakFromHour');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '召回-每轮人数', 'growth.winback.batch', '200', 'Y', 'admin', NOW(), '每轮每类最多评估多少人'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.winback.batch');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '邀请奖励-开关', 'growth.invite.rewardEnabled', 'true', 'Y', 'admin', NOW(), '被邀请人首次可信漫游后给邀请人发徽章(非现金)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.invite.rewardEnabled');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '邀请奖励-每月上限', 'growth.invite.monthlyCapPerInviter', '10', 'Y', 'admin', NOW(), '每位邀请人每个自然月最多得奖几次'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.invite.monthlyCapPerInviter');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT 'App-App Store 地址', 'growth.app.appStoreUrl', '', 'Y', 'admin', NOW(), '上架后填 https://apps.apple.com/cn/app/id<数字>;空 = 小程序与网页显示“即将上架”'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.app.appStoreUrl');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT 'App-通用链接页', 'growth.app.universalLinkBase', 'https://www.chengyinhub.com/door', 'Y', 'admin', NOW(), '已装 App 时由 iOS 直接打开 App(AASA /door)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'growth.app.universalLinkBase');

-- 召回任务:每小时第 7 分钟。登记为运行状态,growth.winback.mode=OFF 时整轮空跑。
INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '成长召回(连续天数/新地点/附近活动)', 'DEFAULT', 'growthWinbackTask.run()', '0 7 * * * ?', '3', '1', '0',
       'system', NOW(), 'R11 growth-loops;发不发看 sys_config growth.winback.mode(默认 OFF)'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'growthWinbackTask.run()');

-- 只读核验:
-- SELECT config_key, config_value FROM sys_config WHERE config_key LIKE 'growth.winback.%' OR config_key LIKE 'growth.invite.%' OR config_key LIKE 'growth.app.%';
-- SELECT badge_code FROM growth_badge WHERE badge_code LIKE 'INVITE\_%';
-- SELECT job_id, status FROM sys_job WHERE invoke_target = 'growthWinbackTask.run()';
