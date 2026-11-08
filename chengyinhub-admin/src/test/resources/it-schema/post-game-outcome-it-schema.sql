-- B8 完局维护与有效复报 只读 IT 建表脚本（任务自有 scratch schema，仅测试用）。
--
-- 为什么是这份文件而不是自造宽松 schema：
--   PostGameOutcomeMapper.xml 读的是生产真表。若这里手写一份「少几列、类型放宽」的表，
--   列名/类型/可空性写错时 IT 会假绿，而真库上是红的。所以下面每一段都是从仓库里的
--   权威来源**逐字摘出**，不做任何改写：
--     · sql/base_schema_20260818.sql                —— 生产结构导出（7 张既有表）
--     · chengyinhub-admin/sql/migration_community_post_platform_20260909.sql
--           —— biz_event_log.actor_scope（base_schema 之后加的列）、广场三张表
--     · chengyinhub-admin/sql/migration_20260823_topic_route_graph.sql —— topic_route_session
--     · chengyinhub-admin/sql/migration_20260817_tandianri_r6_product_contracts.sql
--           —— topic_completion_xp_award
--
-- 顺序 = 依赖顺序：community_guideline_version 必须早于 community_post（后者有 FK 指向前者）。
-- 只用在 MybatisItDatabase 分配的一次性 scratch 库里；ItSchema 会按表名先 DROP 再建。

-- biz_event_log（base_schema_20260818.sql 原文）
CREATE TABLE `biz_event_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_name` varchar(80) NOT NULL COMMENT '事件名称',
  `member_id` bigint DEFAULT NULL COMMENT '会员ID',
  `anonymous_id` varchar(80) DEFAULT NULL COMMENT '匿名ID',
  `session_id` varchar(80) DEFAULT NULL COMMENT '会话ID',
  `source` varchar(80) DEFAULT NULL COMMENT '来源',
  `page_path` varchar(255) DEFAULT NULL COMMENT '页面路径',
  `biz_type` varchar(40) DEFAULT NULL COMMENT '业务类型',
  `biz_id` bigint DEFAULT NULL COMMENT '业务ID',
  `city_code` varchar(40) DEFAULT NULL COMMENT '城市编码',
  `properties_json` text COMMENT '扩展属性JSON',
  `idempotency_key` varchar(120) DEFAULT NULL COMMENT '幂等键',
  `occurred_at` datetime NOT NULL COMMENT '发生时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_event_idem` (`event_name`,`member_id`,`idempotency_key`),
  KEY `idx_biz_event_name_time` (`event_name`,`occurred_at`),
  KEY `idx_biz_event_member_time` (`member_id`,`occurred_at`),
  KEY `idx_biz_event_biz_time` (`biz_type`,`biz_id`,`occurred_at`),
  KEY `idx_biz_event_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=215 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3行为事件日志';

-- biz_event_log.actor_scope（migration_community_post_platform_20260909.sql 原文；base_schema 之后新增）
ALTER TABLE biz_event_log ADD COLUMN actor_scope VARCHAR(96) NOT NULL DEFAULT 'ANONYMOUS' AFTER idempotency_key;

-- cms_registration（base_schema_20260818.sql 原文）
CREATE TABLE `cms_registration` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '报名ID',
  `registration_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '报名编号(唯一)',
  `parent_order_no` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '父订单号 同一支付的多条记录共享',
  `request_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'BE-03 幂等键:前端结账 UUID',
  `request_fingerprint` char(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '服务端规范化请求指纹',
  `owner_type` int DEFAULT '0' COMMENT '拥有类型1、主题  2、活动',
  `owner_id` bigint NOT NULL COMMENT '主题ID/活动ID',
  `activity_title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '活动/主题名称(冗余)',
  `ticket_id` bigint NOT NULL COMMENT '票种ID',
  `ticket_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '票种名称(冗余)',
  `ticket_price` decimal(10,2) NOT NULL COMMENT '票种价格(冗余)',
  `member_id` bigint NOT NULL COMMENT '用户ID',
  `real_name` varchar(768) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '真实姓名(AES-GCM密文)',
  `phone` varchar(256) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '手机号(AES-GCM密文)',
  `phone_hash` char(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '手机号HMAC盲索引',
  `email` varchar(768) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '邮箱(AES-GCM密文)',
  `address` varchar(768) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '地址(AES-GCM密文)',
  `address_id` bigint DEFAULT NULL COMMENT '地址ID',
  `participate_date` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '参与日期 yyyy-MM-dd（自由探索）',
  `order_num` int DEFAULT '0' COMMENT '订票数量',
  `original_amount` decimal(10,2) NOT NULL COMMENT '原始金额',
  `discount_amount` decimal(10,2) DEFAULT NULL COMMENT '折扣金额',
  `total_amount` decimal(10,2) NOT NULL COMMENT '应付总额',
  `point_used` int DEFAULT '0' COMMENT '使用积分数量',
  `point_deduct_amount` decimal(10,2) DEFAULT NULL COMMENT '积分抵扣金额',
  `point_deduct_rate` decimal(5,4) DEFAULT '0.0100' COMMENT '积分抵扣比例(1积分=0.01元)',
  `max_point_deduct` decimal(10,2) DEFAULT NULL COMMENT '最大可抵扣金额',
  `payable_amount` decimal(10,2) NOT NULL COMMENT '实际需支付金额',
  `payment_status` tinyint NOT NULL DEFAULT '0' COMMENT '支付状态：0待支付 1支付中 2已支付 3支付失败 4已退款',
  `payment_type` tinyint DEFAULT NULL COMMENT '支付方式：0纯积分 1纯微信 2积分+微信',
  `payment_time` datetime DEFAULT NULL COMMENT '支付时间',
  `transaction_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '支付交易号',
  `point_payment_amount` decimal(10,2) DEFAULT NULL COMMENT '积分支付金额',
  `wechat_payment_amount` decimal(10,2) DEFAULT NULL COMMENT '微信支付金额',
  `fee_platform_rate` decimal(10,4) DEFAULT NULL COMMENT '下单冻结平台费率',
  `registration_status` tinyint NOT NULL DEFAULT '1' COMMENT '报名状态：1待支付 2报名成功 3已取消 4已过期',
  `pay_expire_time` datetime NOT NULL COMMENT '支付过期时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `verification_code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '核销码',
  `qrcode_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '核销二维码图片URL',
  `verification_status` tinyint DEFAULT '0' COMMENT '核销状态：0未核销 1已核销',
  `verification_time` datetime DEFAULT NULL COMMENT '核销时间',
  `verifier_id` bigint DEFAULT NULL COMMENT '核销人ID',
  `points_returned` tinyint(1) DEFAULT '0' COMMENT '积分是否返还 0未返  1已返',
  `topic_id` bigint DEFAULT NULL COMMENT 'M2 自玩:主题通行证的主题ID(配合 owner_type/activity_id=0)',
  `purchase_kind` tinyint DEFAULT NULL COMMENT '购买类型 1有人带票GUIDED_TICKET/2自玩通行证SELF_PLAY_PASS/3自由探索票EXPLORE_PASS。NULL=存量未回填,读侧回落推断',
  `expires_at` datetime DEFAULT NULL COMMENT 'M2 自玩:通行证有效期(购后90天)',
  `refund_policy_code` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '退款政策标识(如EXPLORE_END_100_0)。下单时快照,NULL=存量单,读侧回落现取',
  `refund_policy_version` int DEFAULT NULL COMMENT '退款政策版本号。同一code的参数调整靠它区分。NULL同上',
  `refund_deadline` datetime DEFAULT NULL COMMENT '可退截止点快照(③=票end_time)。「别再算了」的那一列:锚点字段卖票后还能改,现取=追溯改写已售单的退款窗口',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_registration_no` (`registration_no`) USING BTREE,
  UNIQUE KEY `uk_reg_member_request` (`member_id`,`request_id`),
  KEY `idx_payment_status` (`payment_status`) USING BTREE,
  KEY `idx_expire_time` (`pay_expire_time`) USING BTREE,
  KEY `idx_cms_registration_parent_order` (`parent_order_no`),
  KEY `idx_cms_registration_participate` (`owner_id`,`participate_date`),
  KEY `idx_reg_paystatus_ctime` (`payment_status`,`create_time`),
  KEY `idx_reg_phone_hash` (`phone_hash`)
) ENGINE=InnoDB AUTO_INCREMENT=74 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='活动/主题报名表';

-- refund_application（base_schema_20260818.sql 原文）
CREATE TABLE `refund_application` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `refund_no` varchar(64) DEFAULT NULL COMMENT '退款单号',
  `source_type` varchar(32) NOT NULL COMMENT '来源类型 registration/order/coupon',
  `source_id` bigint NOT NULL COMMENT '来源ID',
  `owner_type` bigint DEFAULT NULL COMMENT '拥有类型 1主题 2活动',
  `owner_id` bigint DEFAULT NULL COMMENT '主题/活动ID',
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `merchant_id` bigint DEFAULT NULL COMMENT '商家ID',
  `paid_amount` decimal(18,2) DEFAULT '0.00' COMMENT '支付金额',
  `refund_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '退款金额',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态 0待审核 1通过 2驳回',
  `reason` varchar(500) DEFAULT NULL COMMENT '申请原因',
  `reject_reason` varchar(500) DEFAULT NULL COMMENT '驳回/审核原因',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `adjustment_created` tinyint NOT NULL DEFAULT '0' COMMENT '是否已生成冲销',
  `idempotency_key` varchar(128) DEFAULT NULL COMMENT '幂等键',
  `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标记',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `payout_status` tinyint NOT NULL DEFAULT '0' COMMENT '打款结果:0处理中 1原路成功 2原路失败待人工 3人工已退待复核 4双人复核完成',
  `payout_submission_state` varchar(32) NOT NULL DEFAULT 'LEGACY_RECONCILE' COMMENT '原路退款提交态:历史待核对/未发起/提交中/处理中/终态',
  `payout_method` varchar(32) DEFAULT NULL COMMENT '人工退款方式',
  `payout_receipt_url` varchar(512) DEFAULT NULL COMMENT '人工退款回执凭证URL',
  `payout_handler_id` bigint DEFAULT NULL COMMENT '人工处理人ID',
  `payout_handled_at` datetime DEFAULT NULL COMMENT '人工处理时间',
  `payout_reviewer2_id` bigint DEFAULT NULL COMMENT '双人复核第二复核人ID',
  `payout_reviewed2_at` datetime DEFAULT NULL COMMENT '双人复核时间',
  `payout_remark` varchar(500) DEFAULT NULL COMMENT '人工处理备注/原路失败原因',
  `payout_lease_token` varchar(64) DEFAULT NULL COMMENT '退款派发 fencing token',
  `payout_lease_owner` bigint DEFAULT NULL COMMENT '人工打款租约 owner',
  `payout_lease_expires_at` datetime(3) DEFAULT NULL COMMENT '人工打款租约到期时间',
  `created_by_user_id` bigint DEFAULT NULL COMMENT '后台建单人sys_user_id(maker≠checker用;C端/系统发起为NULL)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_idempotency` (`idempotency_key`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  KEY `idx_source` (`source_type`,`source_id`),
  KEY `idx_merchant_status` (`merchant_id`,`status`),
  KEY `idx_refund_payout_status` (`payout_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3退款申请';

-- player_badge（base_schema_20260818.sql 原文）
CREATE TABLE `player_badge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `badge_code` varchar(64) NOT NULL COMMENT '徽章code(FIRST_STEP/TOPIC_CLEAR/STREAK_x/MILE_x)',
  `badge_name` varchar(128) DEFAULT NULL COMMENT '徽章名(冗余,展示用)',
  `icon_url` varchar(255) DEFAULT NULL COMMENT '徽章图标',
  `source_type` int DEFAULT NULL COMMENT '1首次 2通关 3里程 4连续 5等级',
  `source_id` bigint DEFAULT '0' COMMENT '来源对象ID(通关=topicId,其余0)',
  `obtain_time` datetime DEFAULT NULL,
  `del_flag` int DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_badge` (`member_id`,`badge_code`,`source_id`),
  KEY `idx_badge_code_source` (`badge_code`,`source_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='玩家已获徽章';

-- roam_session（base_schema_20260818.sql 原文）
CREATE TABLE `roam_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `recall_sent_at` datetime DEFAULT NULL COMMENT '召回消息发送时间',
  `recall_attempts` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '召回尝试次数',
  `recall_last_attempt_at` datetime DEFAULT NULL COMMENT '最近召回尝试时间',
  `distance_m` int DEFAULT '0' COMMENT '本次里程(米)',
  `tiles_revealed` int DEFAULT '0' COMMENT '本次新点亮格子数(结算写入)',
  `pois_found` int DEFAULT '0' COMMENT '本次新发现 POI 数',
  `xp_awarded` int DEFAULT '0' COMMENT '本次实发探索值(日限后)',
  `status` tinyint DEFAULT '0' COMMENT '0 进行中 1 已结算',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_member` (`member_id`),
  KEY `idx_roam_session_recall` (`status`,`recall_sent_at`,`finished_at`,`recall_attempts`,`recall_last_attempt_at`)
) ENGINE=InnoDB AUTO_INCREMENT=30 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P2 漫游会话';

-- cms_topic_completion_coupon_reward_log（base_schema_20260818.sql 原文）
CREATE TABLE `cms_topic_completion_coupon_reward_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `coupon_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_topic_member` (`topic_id`,`member_id`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主题通关奖励券发放幂等日志';

-- roam_poi_reward_log（base_schema_20260818.sql 原文）
CREATE TABLE `roam_poi_reward_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `poi_id` bigint NOT NULL,
  `member_id` bigint NOT NULL COMMENT '触发的玩家',
  `coupon_id` bigint DEFAULT NULL COMMENT '券模板 sms_coupon.id',
  `coupon_history_id` bigint DEFAULT NULL COMMENT '发放的券包记录 sms_coupon_history.id',
  `trigger_lat` decimal(10,6) DEFAULT NULL COMMENT '触发点(审计)',
  `trigger_lng` decimal(10,6) DEFAULT NULL,
  `status` tinyint DEFAULT '1' COMMENT '1已领 2已核销',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_poi_member` (`poi_id`,`member_id`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='据点互动领券记录(uk 防重发券)';

-- topic_route_session（migration_20260823_topic_route_graph.sql 原文）
CREATE TABLE IF NOT EXISTS topic_route_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '活动场次；自玩为0',
  topic_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default',
  config_version BIGINT NOT NULL,
  config_snapshot_json JSON NOT NULL,
  state_json JSON NOT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0进行中 1已到终点',
  current_node_id BIGINT DEFAULT NULL,
  recommended_node_id BIGINT DEFAULT NULL,
  seed BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  completed_at DATETIME(3) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_topic_route_owner (activity_id,topic_id,member_id,play_instance_id),
  KEY idx_topic_route_current (topic_id,current_node_id,status),
  KEY idx_topic_route_member (member_id,status,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家主题地点路线权威会话';

-- topic_completion_xp_award（migration_20260817_tandianri_r6_product_contracts.sql 原文）
CREATE TABLE IF NOT EXISTS topic_completion_xp_award (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  member_id   BIGINT NOT NULL,
  topic_id    BIGINT NOT NULL,
  xp_amount   INT NOT NULL,
  granted_at  DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_topic_completion_xp_member_topic (member_id, topic_id),
  KEY idx_topic_completion_xp_topic (topic_id),
  CONSTRAINT ck_topic_completion_xp_positive CHECK (xp_amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家每期通关 XP 一次性发放事实';

-- community_guideline_version（community_post 的 FK 目标，必须先建）
CREATE TABLE IF NOT EXISTS community_guideline_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  version_code VARCHAR(32) NOT NULL,
  title VARCHAR(100) NOT NULL,
  summary VARCHAR(500) NOT NULL,
  content_json JSON NOT NULL,
  applies_to VARCHAR(32) NOT NULL DEFAULT 'PLAZA',
  status VARCHAR(16) NOT NULL,
  effective_at DATETIME NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_guideline_version (version_code),
  KEY idx_community_guideline_active (applies_to, status, effective_at, id),
  CONSTRAINT chk_community_guideline_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT chk_community_guideline_json CHECK (JSON_VALID(content_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广场社区规范版本';

-- community_post（migration_community_post_platform_20260909.sql 原文）
CREATE TABLE IF NOT EXISTS community_post (
  id BIGINT NOT NULL AUTO_INCREMENT,
  author_id BIGINT NOT NULL,
  community_id BIGINT NULL,
  client_request_id VARCHAR(96) NOT NULL,
  post_type VARCHAR(32) NOT NULL,
  title VARCHAR(120) NULL,
  body VARCHAR(5000) NOT NULL,
  audience VARCHAR(16) NOT NULL,
  comment_policy VARCHAR(16) NOT NULL,
  reply_approval_enabled TINYINT(1) NOT NULL DEFAULT 0,
  slow_mode_seconds INT NOT NULL DEFAULT 0,
  lifecycle VARCHAR(16) NOT NULL,
  location_precision VARCHAR(16) NOT NULL DEFAULT 'NONE',
  city_code VARCHAR(32) NULL,
  poi_name VARCHAR(120) NULL,
  approximate_geohash VARCHAR(16) NULL COMMENT '最多保留模糊网格，禁止精确坐标',
  recommendation_eligible TINYINT(1) NOT NULL DEFAULT 1,
  search_eligible TINYINT(1) NOT NULL DEFAULT 1,
  interaction_enabled TINYINT(1) NOT NULL DEFAULT 1,
  disclosure_type VARCHAR(24) NOT NULL DEFAULT 'NONE',
  safety_labels_json JSON NOT NULL,
  guideline_version_id BIGINT NOT NULL,
  like_count INT UNSIGNED NOT NULL DEFAULT 0,
  comment_count INT UNSIGNED NOT NULL DEFAULT 0,
  bookmark_count INT UNSIGNED NOT NULL DEFAULT 0,
  share_count INT UNSIGNED NOT NULL DEFAULT 0,
  published_at DATETIME NULL,
  edited_at DATETIME NULL,
  deleted_at DATETIME NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_request (author_id, client_request_id),
  KEY idx_community_post_public (lifecycle, audience, published_at, id),
  KEY idx_community_post_author (author_id, lifecycle, id),
  KEY idx_community_post_city (city_code, lifecycle, published_at, id),
  KEY idx_community_post_community (community_id, lifecycle, published_at, id),
  CONSTRAINT chk_community_post_type CHECK (post_type IN ('MOMENT','ACTIVITY_RECAP','ROUTE_DISCOVERY','CITY_TIP','CLUB_ANNOUNCEMENT')),
  CONSTRAINT chk_community_post_audience CHECK (audience IN ('PUBLIC','FOLLOWERS','COMMUNITY','PRIVATE')),
  CONSTRAINT chk_community_post_comment CHECK (comment_policy IN ('EVERYONE','FOLLOWERS','MEMBERS','MENTIONED','OFF')),
  CONSTRAINT chk_community_post_lifecycle CHECK (lifecycle IN ('DRAFT','CHECKING','PUBLISHED','LIMITED','HIDDEN','REMOVED','DELETED')),
  CONSTRAINT chk_community_post_location CHECK (location_precision IN ('NONE','CITY','APPROXIMATE','EXACT')),
  CONSTRAINT chk_community_post_location_payload CHECK (
    (location_precision = 'NONE' AND city_code IS NULL AND poi_name IS NULL AND approximate_geohash IS NULL)
    OR (location_precision = 'CITY' AND CHAR_LENGTH(TRIM(city_code)) BETWEEN 1 AND 32
        AND approximate_geohash IS NULL)
    OR (location_precision = 'APPROXIMATE' AND CHAR_LENGTH(TRIM(city_code)) BETWEEN 1 AND 32
        AND approximate_geohash IS NOT NULL
        AND approximate_geohash REGEXP '^[0123456789bcdefghjkmnpqrstuvwxyz]{4,8}$')
    OR (location_precision = 'EXACT' AND audience = 'PRIVATE' AND approximate_geohash IS NOT NULL
        AND approximate_geohash REGEXP '^[0123456789bcdefghjkmnpqrstuvwxyz]{9,12}$')
  ),
  CONSTRAINT chk_community_post_disclosure CHECK (disclosure_type IN ('NONE','SPONSORED','GIFTED','MERCHANT_OWNER','MERCHANT_EMPLOYEE')),
  CONSTRAINT chk_community_post_safety_json CHECK (JSON_VALID(safety_labels_json)),
  CONSTRAINT chk_community_post_slow_mode CHECK (slow_mode_seconds BETWEEN 0 AND 86400),
  CONSTRAINT fk_community_post_guideline FOREIGN KEY (guideline_version_id)
    REFERENCES community_guideline_version(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广场帖文聚合根';

-- community_post_media（migration_community_post_platform_20260909.sql 原文）
CREATE TABLE IF NOT EXISTS community_post_media (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_member_id BIGINT NOT NULL,
  upload_request_id VARCHAR(96) NOT NULL,
  upload_receipt_hash CHAR(64) NULL,
  post_id BIGINT NULL,
  media_type VARCHAR(20) NOT NULL,
  object_key VARCHAR(500) NOT NULL,
  derived_object_key VARCHAR(500) NULL,
  mime_type VARCHAR(100) NOT NULL,
  byte_size BIGINT UNSIGNED NOT NULL,
  width INT UNSIGNED NULL,
  height INT UNSIGNED NULL,
  duration_ms INT UNSIGNED NULL,
  sort_order INT UNSIGNED NOT NULL DEFAULT 0,
  alt_text VARCHAR(500) NULL,
  content_hash CHAR(64) NOT NULL,
  upload_state VARCHAR(16) NOT NULL,
  review_state VARCHAR(16) NOT NULL,
  retention_state VARCHAR(16) NOT NULL,
  committed_at DATETIME NULL,
  delete_after DATETIME NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_media_request (owner_member_id, upload_request_id),
  UNIQUE KEY uk_community_post_media_receipt (upload_receipt_hash),
  KEY idx_community_post_media_hash (owner_member_id, content_hash),
  KEY idx_community_post_media_post (post_id, sort_order, id),
  KEY idx_community_post_media_cleanup (retention_state, delete_after, id),
  CONSTRAINT chk_community_post_media_type CHECK (media_type IN ('IMAGE','VIDEO','MAP_SNAPSHOT','FILE')),
  CONSTRAINT chk_community_post_media_upload CHECK (upload_state IN ('INITIATED','UPLOADED','COMMITTED','ATTACHED','REJECTED')),
  CONSTRAINT chk_community_post_media_review CHECK (review_state IN ('PENDING','PASS','HOLD','REJECTED')),
  CONSTRAINT chk_community_post_media_retention CHECK (retention_state IN ('ACTIVE','ORPHANED','LEGAL_HOLD','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖文媒体资产与生命周期';

-- game_runtime_session（migration_20260822_advanced_game_runtime.sql 原文）
-- ⚠️ 刻意保留：它是**节点小游戏**会话（node_id/template_id），
--   本 IT 会往里插 status=1 的行来证明它不再被算成整局完成。
CREATE TABLE IF NOT EXISTS game_runtime_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '活动场次;自玩为0',
  topic_id BIGINT NOT NULL,
  node_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  owner_type TINYINT NOT NULL COMMENT '1玩家 2队伍',
  owner_id BIGINT NOT NULL COMMENT 'member_id或play_team.id',
  config_snapshot_json JSON NOT NULL COMMENT '开始时冻结的模板高级玩法配置',
  state_json JSON NOT NULL COMMENT '随机结果/分支/角色/轮次等权威状态',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0进行中 1完成 2失败 3超时',
  score INT NOT NULL DEFAULT 0,
  completed_units INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0 COMMENT 'CAS版本',
  started_at DATETIME(3) NOT NULL,
  deadline_at DATETIME(3) DEFAULT NULL,
  completed_at DATETIME(3) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_runtime_owner (activity_id, topic_id, node_id, owner_type, owner_id),
  KEY idx_runtime_leaderboard (activity_id, topic_id, node_id, status, score, completed_units, completed_at),
  KEY idx_runtime_deadline (status, deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='高级玩法权威运行会话';
