-- P3 Phase C: Merchant CRM and finance foundation.
-- Apply after P3 Phase A/B migrations.

CREATE TABLE IF NOT EXISTS `merchant_customer_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `member_id` bigint NOT NULL COMMENT '用户ID',
  `member_nickname` varchar(100) DEFAULT NULL COMMENT '用户昵称快照',
  `member_phone` varchar(32) DEFAULT NULL COMMENT '用户手机号快照',
  `registration_count` bigint NOT NULL DEFAULT 0 COMMENT '报名次数',
  `order_count` bigint NOT NULL DEFAULT 0 COMMENT '订单次数',
  `order_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '订单金额',
  `coupon_receive_count` bigint NOT NULL DEFAULT 0 COMMENT '领券次数',
  `coupon_verify_count` bigint NOT NULL DEFAULT 0 COMMENT '核销次数',
  `last_interaction_time` datetime DEFAULT NULL COMMENT '最近互动时间',
  `customer_stage` varchar(32) NOT NULL DEFAULT 'new' COMMENT '客户阶段 new/active/lost/vip',
  `city_code` varchar(32) DEFAULT NULL COMMENT '城市编码',
  `tags_json` varchar(1000) DEFAULT NULL COMMENT '标签JSON',
  `del_flag` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记 0正常 2删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_member` (`merchant_id`, `member_id`),
  KEY `idx_merchant_stage` (`merchant_id`, `customer_stage`),
  KEY `idx_merchant_last_interaction` (`merchant_id`, `last_interaction_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3商家客户画像';

CREATE TABLE IF NOT EXISTS `merchant_customer_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `tag_code` varchar(64) NOT NULL COMMENT '标签编码',
  `tag_name` varchar(64) NOT NULL COMMENT '标签名称',
  `tag_color` varchar(32) DEFAULT NULL COMMENT '标签颜色',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序',
  `del_flag` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记 0正常 2删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_tag_code` (`merchant_id`, `tag_code`),
  KEY `idx_merchant_sort` (`merchant_id`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3商家客户标签';

CREATE TABLE IF NOT EXISTS `settlement_batch` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `batch_no` varchar(64) NOT NULL COMMENT '批次号',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `period_start` datetime DEFAULT NULL COMMENT '结算周期开始',
  `period_end` datetime DEFAULT NULL COMMENT '结算周期结束',
  `total_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '结算总额',
  `detail_count` bigint NOT NULL DEFAULT 0 COMMENT '明细数',
  `success_count` bigint NOT NULL DEFAULT 0 COMMENT '成功数',
  `failed_count` bigint NOT NULL DEFAULT 0 COMMENT '失败数',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态 0待审核 1已通过 2已失败',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人ID',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_no` (`batch_no`),
  UNIQUE KEY `uk_idempotency_key` (`idempotency_key`),
  KEY `idx_merchant_status` (`merchant_id`, `status`),
  KEY `idx_period` (`period_start`, `period_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3结算批次';

CREATE TABLE IF NOT EXISTS `settlement_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `batch_id` bigint NOT NULL COMMENT '批次ID',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `source_type` varchar(32) NOT NULL COMMENT '来源类型 order/coupon/activity/topic',
  `source_id` bigint NOT NULL COMMENT '来源ID',
  `receiver_type` varchar(32) NOT NULL COMMENT '收款方类型 merchant/creator/platform',
  `receiver_id` bigint NOT NULL COMMENT '收款方ID',
  `gross_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '原始金额',
  `commission_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '佣金金额',
  `settlement_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '结算金额',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态 0待处理 1成功 2失败',
  `fail_reason` varchar(255) DEFAULT NULL COMMENT '失败原因',
  `idempotency_key` varchar(160) NOT NULL COMMENT '明细幂等键',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_detail_idempotency` (`idempotency_key`),
  UNIQUE KEY `uk_source_receiver` (`source_type`, `source_id`, `receiver_type`, `receiver_id`),
  KEY `idx_batch` (`batch_id`),
  KEY `idx_merchant_status` (`merchant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3结算明细';

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '商家CRM查询', 0, 1, '#', '', 1, 0, 'F', '0', '0', 'business:crm:list', '#', 'system', NOW(), 'P3 Phase C CRM list permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:crm:list');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '商家CRM聚合', 0, 2, '#', '', 1, 0, 'F', '0', '0', 'business:crm:aggregate', '#', 'system', NOW(), 'P3 Phase C CRM aggregate permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:crm:aggregate');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '结算查询', 0, 3, '#', '', 1, 0, 'F', '0', '0', 'business:settlement:list', '#', 'system', NOW(), 'P3 Phase C settlement list permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlement:list');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '结算生成', 0, 4, '#', '', 1, 0, 'F', '0', '0', 'business:settlement:generate', '#', 'system', NOW(), 'P3 Phase C settlement generate permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlement:generate');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '结算打款', 0, 5, '#', '', 1, 0, 'F', '0', '0', 'business:settlement:confirm', '#', 'system', NOW(), 'P3 Phase C settlement payout confirm permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlement:confirm');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '提现审核', 0, 6, '#', '', 1, 0, 'F', '0', '0', 'business:memberwithdrawal:review', '#', 'system', NOW(), 'P3 Phase C guarded withdrawal review permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:memberwithdrawal:review');

SET @p3_parent := (
  SELECT menu_id FROM sys_menu WHERE menu_name = 'P3平台运营' LIMIT 1
);

INSERT INTO sys_menu
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 'P3平台运营', 0, 50, 'p3-platform', NULL, '', '', 1, 0, 'M', '0', '0', '', 'dashboard', 'admin', sysdate(), '', NULL, 'P3平台运营目录'
WHERE @p3_parent IS NULL;

SET @p3_parent := (
  SELECT menu_id FROM sys_menu WHERE menu_name = 'P3平台运营' LIMIT 1
);

UPDATE sys_menu
SET menu_name = '商家CRM',
    parent_id = @p3_parent,
    order_num = 7,
    path = 'crm',
    component = 'business/crm/index',
    query = '',
    route_name = '',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'C',
    visible = '0',
    status = '0',
    icon = 'peoples',
    remark = 'P3商家CRM'
WHERE perms = 'business:crm:list';

INSERT INTO sys_menu
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT '商家CRM', @p3_parent, 7, 'crm', 'business/crm/index', '', '', 1, 0, 'C', '0', '0', 'business:crm:list', 'peoples', 'admin', sysdate(), '', NULL, 'P3商家CRM'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:crm:list');

SET @crm_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:crm:list' LIMIT 1
);

UPDATE sys_menu
SET parent_id = @crm_menu,
    path = '',
    component = '',
    menu_type = 'F',
    visible = '0',
    status = '0'
WHERE perms = 'business:crm:aggregate';

UPDATE sys_menu
SET menu_name = '结算管理',
    parent_id = @p3_parent,
    order_num = 8,
    path = 'settlement',
    component = 'business/settlement/index',
    query = '',
    route_name = '',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'C',
    visible = '0',
    status = '0',
    icon = 'money',
    remark = 'P3结算管理'
WHERE perms = 'business:settlement:list';

INSERT INTO sys_menu
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT '结算管理', @p3_parent, 8, 'settlement', 'business/settlement/index', '', '', 1, 0, 'C', '0', '0', 'business:settlement:list', 'money', 'admin', sysdate(), '', NULL, 'P3结算管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlement:list');

SET @settlement_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:settlement:list' LIMIT 1
);

UPDATE sys_menu
SET parent_id = @settlement_menu,
    path = '',
    component = '',
    menu_type = 'F',
    visible = '0',
    status = '0'
WHERE perms = 'business:settlement:generate';
