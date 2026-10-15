-- Task-only GameplayReskinMySqlIT schema for the real commercial policy reads.
-- oms_ticket and the two cms_activity columns copied exactly from run41-prod-ddl.sql; shared source is unchanged.
DROP TABLE IF EXISTS oms_ticket;
CREATE TABLE `oms_ticket` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '票务发布用户ID',
  `owner_type` int DEFAULT NULL COMMENT '拥有类型1、主题  2、活动',
  `owner_id` bigint DEFAULT '0' COMMENT '主题ID/活动ID',
  `source_ticket_id` bigint DEFAULT NULL COMMENT '活动壳票对应的主题源票 id',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '票务名称',
  `price` decimal(10,2) DEFAULT NULL COMMENT '价格',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `sale_start_time` datetime DEFAULT NULL COMMENT '售票开始时间(发布页填写;仅落库回显,购票闸未接)',
  `sale_end_time` datetime DEFAULT NULL COMMENT '售票结束时间(发布页填写;仅落库回显,购票闸未接)',
  `total_inventory` int DEFAULT NULL COMMENT '总库存',
  `remaining_inventory` int DEFAULT NULL COMMENT '剩余库存',
  `mode` int DEFAULT '1' COMMENT '活动模式 1=城市定向 2=自由探索',
  `sub_type` varchar(8) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '票种子类型 guided有人带/self无人带(仅mode=1城市定向)',
  `meeting_point` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '集合地点',
  `meeting_point_address` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '集合点地址(地图选点文本)',
  `team_size` int DEFAULT '0' COMMENT '建议组队人数',
  `min_people` int DEFAULT NULL COMMENT '最低成团人数',
  `max_people` int DEFAULT NULL COMMENT '成团人数上限',
  `signup_deadline` datetime DEFAULT NULL COMMENT '报名截止时间',
  `gather_lng` decimal(10,6) DEFAULT NULL COMMENT '集合点经度',
  `gather_lat` decimal(10,6) DEFAULT NULL COMMENT '集合点纬度',
  `team_status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '成团状态 0募集中 1已成团 2未成团已散',
  `refund_rule` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '退款规则',
  `refund_supported` tinyint(1) DEFAULT NULL COMMENT '支持随时退款 0否1是(发布页填写;仅落库回显,退款规则未接)',
  `sync_with_theme` tinyint(1) DEFAULT NULL COMMENT '售票时间与主题日期同步 0否1是(发布页填写;仅落库回显,同步逻辑未接)',
  `need_audit` tinyint(1) NOT NULL DEFAULT '0' COMMENT '报名是否需主理人确认',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_oms_ticket_activity_source` (`owner_type`,`owner_id`,`source_ticket_id`,`del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='主题/活动票务表';

ALTER TABLE cms_activity ADD COLUMN `member_id` bigint DEFAULT NULL COMMENT '用户ID';
ALTER TABLE cms_activity ADD COLUMN `club_id` bigint DEFAULT NULL COMMENT 'M1-3 社群活动所属俱乐部ID(有值=社群活动,公域不出);NULL=公开活动';
