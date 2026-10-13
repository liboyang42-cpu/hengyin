-- 玩家游玩进度（扫码打卡）+ 游玩积分规则（2026-06-19）

-- 1) 节点打卡码：每个节点一个随机串，物理二维码内容=该码（主题级共用，贴在地点）
ALTER TABLE `cms_topic_node`
    ADD COLUMN `checkin_code` VARCHAR(64) DEFAULT NULL COMMENT '节点打卡码(扫码内容)，唯一随机串' AFTER `template_id`;
-- 存量节点补码（已有节点生成随机码）
UPDATE `cms_topic_node` SET `checkin_code` = REPLACE(UUID(),'-','') WHERE `checkin_code` IS NULL OR `checkin_code` = '';

-- 2) 玩家节点进度表（进度按 activity_id；唯一键防刷：同一节点反复扫只完成一次）
CREATE TABLE IF NOT EXISTS `player_node_progress` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `member_id`     BIGINT       NOT NULL COMMENT '玩家会员ID',
    `activity_id`   BIGINT       NOT NULL COMMENT '活动ID(进度按活动隔离)',
    `topic_id`      BIGINT       DEFAULT NULL COMMENT '主题ID',
    `node_id`       BIGINT       NOT NULL COMMENT '节点ID',
    `status`        TINYINT      DEFAULT 1 COMMENT '1已打卡',
    `checkin_time`  DATETIME     DEFAULT NULL COMMENT '打卡时间',
    `pic_url`       VARCHAR(255) DEFAULT NULL COMMENT '打卡照片(可选)',
    `longitude`     VARCHAR(32)  DEFAULT NULL COMMENT '打卡经度(腾讯地图开通后回填)',
    `latitude`      VARCHAR(32)  DEFAULT NULL COMMENT '打卡纬度',
    `del_flag`      INT          DEFAULT 0,
    `create_time`   DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member_activity_node` (`member_id`, `activity_id`, `node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家节点打卡进度';

-- 3) 游玩积分规则种子（type=1 积分规则；value=发放积分；可在后台「积分规则」修改）
--    eventType 约定：11 到店核销 / 12 节点打卡 / 13 通关主题
--    注：2=首次活动报名、3=参与活动 已存在并已接线，本次不动。
-- 注：cms_points_setting.id 无 AUTO_INCREMENT(现有 1-7)，必须显式给 id；新用 11/12/13。
INSERT INTO `cms_points_setting` (`id`, `title`, `value`, `description`, `type`, `event_type`, `status`, `sort_order`, `del_flag`, `create_by`, `create_time`)
SELECT * FROM (SELECT 11 AS k, '到店核销' AS a, 5 AS b, '到店核销成功奖励' AS c, 1 AS d, 11 AS e, 1 AS f, 11 AS g, 0 AS h, 'admin' AS i, NOW() AS j) t
WHERE NOT EXISTS (SELECT 1 FROM `cms_points_setting` cps WHERE cps.`type`=1 AND cps.`event_type`=11 AND cps.`del_flag`=0);

INSERT INTO `cms_points_setting` (`id`, `title`, `value`, `description`, `type`, `event_type`, `status`, `sort_order`, `del_flag`, `create_by`, `create_time`)
SELECT * FROM (SELECT 12 AS k, '节点打卡' AS a, 10 AS b, '每完成一个节点打卡奖励' AS c, 1 AS d, 12 AS e, 1 AS f, 12 AS g, 0 AS h, 'admin' AS i, NOW() AS j) t
WHERE NOT EXISTS (SELECT 1 FROM `cms_points_setting` cps WHERE cps.`type`=1 AND cps.`event_type`=12 AND cps.`del_flag`=0);

INSERT INTO `cms_points_setting` (`id`, `title`, `value`, `description`, `type`, `event_type`, `status`, `sort_order`, `del_flag`, `create_by`, `create_time`)
SELECT * FROM (SELECT 13 AS k, '通关主题' AS a, 50 AS b, '完成主题全部节点奖励' AS c, 1 AS d, 13 AS e, 1 AS f, 13 AS g, 0 AS h, 'admin' AS i, NOW() AS j) t
WHERE NOT EXISTS (SELECT 1 FROM `cms_points_setting` cps WHERE cps.`type`=1 AND cps.`event_type`=13 AND cps.`del_flag`=0);
