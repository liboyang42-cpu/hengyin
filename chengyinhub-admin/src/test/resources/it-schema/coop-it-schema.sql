-- 自由合作(coop)四表的 IT 建表脚本 —— **从生产库导出的真实 DDL**(mysqldump --no-data),
-- 2026-08-14 生成。
--
-- 为什么要有这份:CoopMapperMyBatisIT / CoopServiceRealDbIT 的价值正是「拿真实 schema 验
-- mapper XML 的 resultMap 列映射与动态 SQL」,所以不能手写一份简化表 —— 手写就等于拿假 schema
-- 验真映射,验了个寂寞。它们此前靠类注释里的四步人肉建库,结果是**谁都没建、两套从没跑过**。
--
-- ⚠️ 这份是快照,生产加列后它会过期。过期的表现是 IT 变红(缺列),不是静默放行 —— 这是有意的:
--    红了就重新导一次。导出命令见本文件顶部提交说明。
-- ⚠️ 只含结构、零数据。
CREATE TABLE `coop_credit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '被记账方memberId',
  `event_type` tinyint(1) NOT NULL COMMENT '0履约 1超时未配置 2保护期外取消 3保护期内违约',
  `invite_id` bigint DEFAULT NULL COMMENT '关联邀约',
  `reason` varchar(255) DEFAULT NULL COMMENT '理由/备注',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_credit_member` (`member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='协作信用记录';

CREATE TABLE `coop_invite` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `invite_type` tinyint(1) NOT NULL COMMENT '0达人↔商家 1商家→俱乐部 2商家→商家做节点',
  `from_type` varchar(16) NOT NULL COMMENT 'talent/merchant',
  `from_id` bigint NOT NULL COMMENT '发起方memberId',
  `to_type` varchar(16) NOT NULL COMMENT 'merchant/club',
  `to_id` bigint NOT NULL COMMENT '受邀方memberId或clubId',
  `topic_id` bigint DEFAULT NULL COMMENT '关联主题',
  `origin_apply_id` bigint DEFAULT NULL COMMENT '开放报名溯源 coop_club_apply.id(type1 有值则发起方缴押金)',
  `game_id` bigint DEFAULT NULL COMMENT '城市定向:目标游戏(游戏级邀约)',
  `target_level` tinyint(1) DEFAULT '0' COMMENT '槽位粒度 0主题级/1游戏级',
  `node_id` bigint DEFAULT NULL COMMENT '节点',
  `supply_station_id` bigint DEFAULT NULL COMMENT 'Explicit station; historical rows stay NULL (test fixture)',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '状态 0待确认 1已接受 2已拒绝 3已取消 4已顶替 5已过期',
  `message` varchar(500) DEFAULT NULL,
  `handle_time` datetime DEFAULT NULL,
  `handle_reason` varchar(255) DEFAULT NULL COMMENT '受邀方拒绝原因',
  `expire_time` datetime DEFAULT NULL COMMENT '邀约自身过期时间(=主题招募截止,挂起超时判过期)',
  `config_deadline` datetime DEFAULT NULL COMMENT '自由探索:同意后配置游戏死线(超时释放槽位)',
  `share_mode` tinyint(1) DEFAULT NULL COMMENT '报酬模式 0引流/1分成/2固定',
  `share_rate` decimal(5,2) DEFAULT NULL COMMENT '分成型比例(share_mode=1)',
  `fixed_fee` decimal(10,2) DEFAULT NULL COMMENT '固定型每核销人头费(share_mode=2)',
  `deposit_amount` decimal(10,2) DEFAULT NULL COMMENT '保证金额(仅type1俱乐部,固定¥50)',
  `deposit_status` tinyint(1) DEFAULT '0' COMMENT '保证金状态 0无需/1支付中/2已缴冻结/3已扣/4已退/5应缴未缴',
  `deposit_out_trade_no` varchar(64) DEFAULT NULL COMMENT '保证金微信支付商户订单号(前缀DEP)',
  `deposit_refund_no` varchar(64) DEFAULT NULL COMMENT '保证金退款单号(完成原路退)',
  `deposit_refund_state` varchar(24) DEFAULT NULL COMMENT 'PENDING/DISPATCHING/PROCESSING/UNKNOWN/SUCCESS/MANUAL/LEGACY_UNCONFIRMED',
  `deposit_refund_amount` decimal(10,2) DEFAULT NULL COMMENT '派发前固定退款金额',
  `deposit_refund_lease_token` varchar(36) DEFAULT NULL,
  `deposit_refund_lease_at` datetime DEFAULT NULL,
  `deposit_refund_requested_at` datetime DEFAULT NULL,
  `deposit_refund_confirmed_at` datetime DEFAULT NULL,
  `deposit_paid_transaction_id` varchar(64) DEFAULT NULL,
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `del_flag` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_coop_from` (`from_type`,`from_id`,`status`),
  KEY `idx_coop_to` (`to_type`,`to_id`,`status`),
  KEY `idx_coop_topic` (`topic_id`),
  KEY `idx_coop_expire` (`status`,`expire_time`),
  KEY `idx_coop_config` (`status`,`config_deadline`),
  KEY `idx_coop_origin_apply` (`origin_apply_id`),
  UNIQUE KEY `uk_coop_deposit_trade` (`deposit_out_trade_no`),
  UNIQUE KEY `uk_coop_deposit_refund` (`deposit_refund_no`),
  KEY `idx_coop_deposit_refund_reconcile` (`deposit_refund_state`,`deposit_refund_lease_at`)
) ENGINE=InnoDB AUTO_INCREMENT=990024 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='协作邀请';

CREATE TABLE `coop_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `invite_id` bigint DEFAULT NULL COMMENT '来源邀约(邀约侧候选;报名侧为空)',
  `reg_id` bigint DEFAULT NULL COMMENT '来源报名(候选池报名侧;邀约侧为空)',
  `topic_id` bigint DEFAULT NULL COMMENT '主题',
  `game_id` bigint DEFAULT NULL COMMENT '城市定向:游戏',
  `from_id` bigint DEFAULT NULL COMMENT '发起方memberId',
  `to_id` bigint DEFAULT NULL COMMENT '受邀方(merchant=memberId/club=clubId)',
  `to_type` varchar(16) DEFAULT NULL COMMENT '受邀方类型 merchant/club',
  `share_mode` tinyint(1) DEFAULT NULL COMMENT '0引流 1分成 2固定',
  `share_rate` decimal(5,2) DEFAULT NULL COMMENT '分成比例',
  `fixed_fee` decimal(10,2) DEFAULT NULL COMMENT '固定型每核销人头费',
  `perks_snapshot` json DEFAULT NULL COMMENT '同意瞬间的权益快照',
  `terms_version` varchar(16) DEFAULT NULL COMMENT '平台规则版本号',
  `role_snapshot` varchar(32) DEFAULT NULL COMMENT '接受时服务端冻结的合同角色 SUPPLY/ORGANIZER',
  `scope_type_snapshot` varchar(32) DEFAULT NULL COMMENT '接受时冻结的履约范围类型 TOPIC/NODE',
  `scope_id_snapshot` bigint DEFAULT NULL COMMENT '接受时冻结的履约范围 ID',
  `eligible_purchase_kinds_snapshot` varchar(128) DEFAULT NULL COMMENT '接受时服务端冻结的允许 purchase_kind 集合',
  `fulfillment_evidence_rule_snapshot` varchar(64) DEFAULT NULL COMMENT '接受时服务端冻结的履约证据规则',
  `terms_snapshot` text COMMENT '接受时服务端冻结的两轴/金额/权益条款 JSON',
  `deposit_amount_snapshot` decimal(10,2) DEFAULT NULL COMMENT '独立于两轴的接受时保证金快照',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0生效 1作废 2已结算',
  `create_time` datetime DEFAULT NULL,
  `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  `reg_key` bigint GENERATED ALWAYS AS ((case when (`del_flag` = 0) then `reg_id` else NULL end)) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coop_order_reg` (`reg_key`),
  KEY `idx_order_invite` (`invite_id`),
  KEY `idx_order_reg` (`reg_id`),
  KEY `idx_order_topic` (`topic_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合作单(条款快照)';

CREATE TABLE `coop_perk` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `invite_id` bigint DEFAULT NULL COMMENT '所属邀约；开放招商独立供给为空',
  `merchant_id` bigint DEFAULT NULL COMMENT '开放招商独立供给的商家memberId',
  `perk_type` tinyint(1) NOT NULL COMMENT '0礼品 1优惠券 2折扣体验',
  `name` varchar(64) DEFAULT NULL COMMENT '名称',
  `unit_cost` decimal(10,2) DEFAULT NULL COMMENT '单人成本价(商家自报)',
  `quota` int DEFAULT NULL COMMENT '总配额',
  `redeem_rule` varchar(255) DEFAULT NULL COMMENT '核销规则',
  `valid_end` datetime DEFAULT NULL COMMENT '有效期截止',
  `create_time` datetime DEFAULT NULL,
  `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  `retail_value` decimal(10,2) DEFAULT NULL COMMENT '权益零售标价(玩家视角价值);入驻门槛比它,不比unit_cost',
  PRIMARY KEY (`id`),
  KEY `idx_perk_invite` (`invite_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='协作权益申报(礼品/券/折扣)';
