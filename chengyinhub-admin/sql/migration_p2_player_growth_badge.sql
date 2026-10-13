-- P2 玩家成长体系:成长聚合 + 已获徽章
-- 生产库执行请走 chengyin-db-migrate skill,勿直连。幂等:可重复执行(IF NOT EXISTS)。
-- 日期:2026-06-21

-- 玩家成长聚合(每人一行):等级 / 累计打卡 / 累计里程 / 连续打卡
CREATE TABLE IF NOT EXISTS `player_growth` (
  `id`                bigint       NOT NULL AUTO_INCREMENT,
  `member_id`         bigint       NOT NULL                COMMENT '会员ID',
  `level`             int          DEFAULT 1               COMMENT '等级',
  `total_checkins`    int          DEFAULT 0               COMMENT '累计节点完成数',
  `total_mileage`     decimal(10,2) DEFAULT 0.00           COMMENT '累计里程(km)',
  `streak_days`       int          DEFAULT 0               COMMENT '连续打卡天数',
  `last_checkin_date` date         DEFAULT NULL            COMMENT '最近打卡日期(算streak)',
  `update_time`       datetime     DEFAULT NULL,
  `create_time`       datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家成长聚合(等级/里程/连续打卡)';

-- 玩家已获徽章。唯一键 (member_id,badge_code,source_id) 保证幂等不重复发
CREATE TABLE IF NOT EXISTS `player_badge` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `member_id`    bigint       NOT NULL                 COMMENT '会员ID',
  `badge_code`   varchar(64)  NOT NULL                 COMMENT '徽章code(FIRST_STEP/TOPIC_CLEAR/STREAK_x/MILE_x)',
  `badge_name`   varchar(128) DEFAULT NULL             COMMENT '徽章名(冗余,展示用)',
  `icon_url`     varchar(255) DEFAULT NULL             COMMENT '徽章图标',
  `source_type`  int          DEFAULT NULL             COMMENT '1首次 2通关 3里程 4连续 5等级',
  `source_id`    bigint       DEFAULT 0                COMMENT '来源对象ID(通关=topicId,其余0)',
  `obtain_time`  datetime     DEFAULT NULL,
  `del_flag`     int          DEFAULT 0,
  `create_time`  datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_badge` (`member_id`,`badge_code`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家已获徽章';

-- 说明:徽章定义(名称/图标/阈值)P2 先在 PlayerProgressionServiceImpl 内按 code 维护;
-- 后续可配置化时新增 cms_badge 定义表,后台「徽章规则」维护,本表 badge_name/icon_url 改为读定义表。
