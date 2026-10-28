-- Isolated test schema: exact two legacy tables from public baseline.
CREATE TABLE `official_broadcast` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint DEFAULT NULL COMMENT '关联 official_event.id,NULL=纯公告',
  `title` varchar(128) NOT NULL COMMENT '通知标题',
  `audience` varchar(32) NOT NULL COMMENT '目标角色逗号集:player,club,merchant',
  `copy_mode` tinyint DEFAULT '1' COMMENT '1一起发(统一文案) 2分开发(分角色文案)',
  `content_json` text COMMENT '一起发={title,sub},分开发={player:{..},club:{..},merchant:{..}}',
  `channels` varchar(32) NOT NULL DEFAULT 'inapp' COMMENT '触达渠道逗号集:inapp,subscribe',
  `city` varchar(32) DEFAULT NULL COMMENT '城市名关键词,NULL=全国全体该角色',
  `scheduled_time` datetime DEFAULT NULL COMMENT 'NULL=立即发',
  `status` tinyint DEFAULT '0' COMMENT '0草稿 1待发 2已发 3已撤',
  `reached_count` int DEFAULT '0' COMMENT '触达数(冗余统计)',
  `sender_account` bigint DEFAULT NULL COMMENT '发起白名单账号 member_id',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_event` (`event_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方通知/广播';
CREATE TABLE `official_broadcast_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `broadcast_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `role` varchar(16) DEFAULT NULL COMMENT 'player/club/merchant',
  `channel` varchar(16) DEFAULT NULL COMMENT 'inapp/subscribe',
  `reached` tinyint DEFAULT '0' COMMENT '1已触达',
  `clicked` tinyint DEFAULT '0' COMMENT '1已点击',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bc_member_channel` (`broadcast_id`,`member_id`,`channel`),
  KEY `idx_broadcast` (`broadcast_id`),
  KEY `idx_obl_member_channel_reached_time` (`member_id`,`channel`,`reached`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方通知分发日志(uk 防重发)';

-- Minimal existing-member lock fixture; production uses the existing ums_member primary key.
-- Only lockMemberById is invoked here; unrelated member fields and money operations are not exercised.
CREATE TABLE ums_member (id bigint NOT NULL PRIMARY KEY) ENGINE=InnoDB;
