-- BE-14 多作用域榜单 MySQL IT 的本用例自有表（仅测试用 scratch schema）。
--
-- 为什么在这里手写而不是再塞一份完整生产 dump：
--   cms_topic / cms_registration / refund_application / topic_route_session 已由
--   it-schema/cms-topic-it-schema.sql 与 it-schema/post-game-outcome-it-schema.sql
--   提供生产真实 DDL；这里只补 4 张该脚本没覆盖、但本卡 SQL 必须真读的表。
--   列定义逐字取自：
--     · sql/base_schema_20260818.sql                          —— ums_member / club / club_member
--     · sql/base_schema_20260818.sql + chengyinhub-admin/sql/
--       migration_theme_runtime_20261004.sql                  —— player_node_progress（含
--       play_instance_id 与 uk_progress/uk_member_activity_node 唯一键）
--     · play_instance_id/puzzle_score/completion_mode 的真实形状另见
--       it-schema/journey-full-flow-it-schema.sql
--   只建结构、不塞数据；ItSchema 会按表名先 DROP 再建。

CREATE TABLE `ums_member` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会员ID',
  `nickname` varchar(50) DEFAULT '' COMMENT '昵称',
  `avatar` varchar(255) DEFAULT '' COMMENT '头像',
  `del_flag` tinyint(1) DEFAULT '0' COMMENT '删除标记 0存在 2删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员表(本用例只保留榜单投影要用的公开列)';

CREATE TABLE `club` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '拥有者会员ID',
  `name` varchar(64) NOT NULL COMMENT '俱乐部名称',
  `city` varchar(64) DEFAULT NULL COMMENT '所在城市',
  `can_design_route` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计路线 0否1是',
  `can_design_task` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计任务 0否1是',
  `can_npc` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否提供NPC 0否1是',
  `can_merchant_coop` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否商家合作 0否1是',
  `has_guide_cert` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否有导游/旅游资质 0否1是',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '审核状态 0待审核 1通过 2驳回',
  `level` tinyint(1) NOT NULL DEFAULT '0' COMMENT '俱乐部定级 0未定级 1-5',
  `member_count` int NOT NULL DEFAULT '0' COMMENT '成员数(冗余)',
  `priority_signup_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '成员优先报名 0否1是',
  `member_reserved_quota` int NOT NULL DEFAULT '0' COMMENT '成员保留报名名额',
  `join_policy` tinyint(1) NOT NULL DEFAULT '0' COMMENT '加入策略:0公开直进 1需主理人审批',
  `public_visible` tinyint(1) DEFAULT NULL COMMENT '公开可见 0仅成员 1/null对外可见',
  `del_flag` int NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部';

CREATE TABLE `club_member` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `club_id` bigint NOT NULL COMMENT '俱乐部ID',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `role` tinyint(1) NOT NULL DEFAULT '0' COMMENT '成员角色 0成员(群主=club.member_id)',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '成员状态:0待审批 1正常 2已拒绝',
  `join_time` datetime DEFAULT NULL COMMENT '加入时间',
  `del_flag` int DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_club_member_uniq` (`club_id`,`member_id`),
  KEY `idx_club` (`club_id`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部成员';

CREATE TABLE `player_node_progress` (
  `play_instance_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '主题游玩实例',
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `activity_id` bigint DEFAULT '0',
  `topic_id` bigint NOT NULL,
  `node_id` bigint NOT NULL,
  `status` int DEFAULT '0',
  `checkin_time` datetime DEFAULT NULL,
  `pic_url` varchar(255) DEFAULT NULL,
  `review_status` int DEFAULT NULL,
  `longitude` varchar(32) DEFAULT NULL,
  `latitude` varchar(32) DEFAULT NULL,
  `puzzle_score` int DEFAULT NULL,
  `completion_mode` varchar(32) DEFAULT NULL,
  `del_flag` tinyint DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_progress` (`member_id`,`topic_id`,`node_id`,`activity_id`,`play_instance_id`),
  UNIQUE KEY `uk_member_activity_node` (`member_id`,`activity_id`,`node_id`,`play_instance_id`),
  KEY `idx_progress_scope` (`member_id`,`topic_id`,`node_id`,`del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家节点打卡进度';
