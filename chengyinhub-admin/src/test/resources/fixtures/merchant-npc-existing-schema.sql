-- Test-only exact baseline CREATE TABLE extracts; no production deployment.
CREATE TABLE `mms_merchant` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '会员ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商家名称',
  `wechat` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '微信二维码',
  `category_id` int DEFAULT '0' COMMENT '分类ID',
  `logo` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商家LOGO',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '介绍',
  `derivatives` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '衍生品',
  `website` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品牌网址',
  `preference` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品牌偏好',
  `phone` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '联系电话',
  `address` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '商户地址',
  `business_time` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '营业时间',
  `business_license` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '营业执照',
  `status` int DEFAULT '0' COMMENT '入住审核状态0 待审核  1审核通过  2审核失败',
  `reson` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `account_status` int DEFAULT '0' COMMENT '账号状态：1启用  2禁用',
  `business_status` tinyint NOT NULL DEFAULT '1' COMMENT '营业状态 1营业中 0已打烊',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `capacity` int DEFAULT NULL COMMENT '可容纳/承接人数',
  `available_time` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '可承接时段',
  `suit_activity_types` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '适合活动类型(逗号分隔)',
  `charge_type` tinyint(1) NOT NULL DEFAULT '0' COMMENT '承接收费 0免费 1收费',
  `demand` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '诉求',
  `coop_open` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否开放被达人对接 0否1是',
  `cover_image` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-封面头图URL',
  `slogan` varchar(60) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-一句话slogan',
  `city_role` varchar(30) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-城市角色名',
  `story_title` varchar(60) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-品牌故事标题(正文用description)',
  `gallery` text COLLATE utf8mb4_general_ci COMMENT '店铺装修-门店相册JSON数组',
  `tags` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-特色标签JSON/逗号',
  `featured_type` tinyint DEFAULT '0' COMMENT '店铺装修-招牌主推类型0无/1活动主题/2券',
  `featured_id` bigint DEFAULT NULL COMMENT '店铺装修-招牌主推目标id',
  `location_lat` decimal(10,7) DEFAULT NULL COMMENT '商家纬度(手动打点/LBS)',
  `location_lng` decimal(10,7) DEFAULT NULL COMMENT '商家经度',
  `location_verified` tinyint DEFAULT '0' COMMENT '0未定位 1商家地图选点 2按地址反查推断',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='商家表';
CREATE TABLE `audit_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_type` varchar(40) NOT NULL COMMENT '业务类型',
  `biz_id` bigint NOT NULL COMMENT '业务ID',
  `submitter_id` bigint DEFAULT NULL COMMENT '提交人会员ID',
  `source_type` varchar(40) DEFAULT NULL COMMENT '来源类型',
  `risk_level` varchar(20) NOT NULL DEFAULT 'NORMAL' COMMENT '风险等级',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0待审 1通过 2驳回 3升级 4取消',
  `current_assignee` bigint DEFAULT NULL COMMENT '当前处理人',
  `submitted_at` datetime NOT NULL COMMENT '提交时间',
  `deadline_at` datetime DEFAULT NULL COMMENT '处理截止时间',
  `snapshot_json` mediumtext COMMENT '提交快照',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `create_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `open_guard` tinyint GENERATED ALWAYS AS ((case when (`status` in (0,3)) then 1 else NULL end)) STORED COMMENT '未结任务唯一闸：终态为NULL以允许重复提审',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audit_biz_open` (`biz_type`,`biz_id`,`open_guard`),
  KEY `idx_audit_status_deadline` (`status`,`deadline_at`),
  KEY `idx_audit_submitter` (`submitter_id`,`submitted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3统一审核任务';
CREATE TABLE `audit_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` bigint NOT NULL COMMENT '审核任务ID',
  `action` varchar(40) NOT NULL COMMENT '动作',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `reason_code` varchar(60) DEFAULT NULL COMMENT '原因编码',
  `reason_text` varchar(500) DEFAULT NULL COMMENT '原因说明',
  `snapshot_json` mediumtext COMMENT '审核快照',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_record_task` (`task_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3统一审核记录';

-- Exact table-only governance ALTER statements; no menus, RBAC, or historical data.
ALTER TABLE audit_record
  ADD COLUMN reverted_record_id BIGINT NULL COMMENT 'REVERT 指向被撤销的原审批记录 id' AFTER snapshot_json,
  ADD KEY idx_audit_record_reverted (reverted_record_id);
ALTER TABLE business_audit_event
    ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
      COMMENT '主体命名空间：UNKNOWN/MEMBER/SYS_USER；不猜历史身份' AFTER owner_member_id,
    MODIFY COLUMN request_id VARCHAR(64) NOT NULL COMMENT '稳定业务请求键；禁止 NULL/空串';
