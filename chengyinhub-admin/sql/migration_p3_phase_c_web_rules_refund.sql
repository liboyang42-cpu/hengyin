-- P3 Phase C: Web settlement rules and refund review.
-- Apply after migration_p3_phase_c_crm_finance.sql.

CREATE TABLE IF NOT EXISTS `settlement_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_id` bigint NOT NULL DEFAULT 0 COMMENT '商家ID，0为平台默认',
  `biz_type` varchar(32) NOT NULL COMMENT '业务类型 activity/topic/coupon/order',
  `rule_name` varchar(100) DEFAULT NULL COMMENT '规则名称',
  `settlement_cycle` varchar(32) NOT NULL DEFAULT 'monthly' COMMENT '结算周期 daily/weekly/monthly/manual',
  `platform_commission_rate` decimal(10,4) NOT NULL DEFAULT 0.1000 COMMENT '平台抽成比例',
  `merchant_rate` decimal(10,4) NOT NULL DEFAULT 0.9000 COMMENT '商家比例',
  `creator_rate` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT '创作者比例',
  `inviter_rate` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT '邀请人比例',
  `min_settlement_amount` decimal(18,2) NOT NULL DEFAULT 100.00 COMMENT '最低结算金额',
  `refund_deadline_hours` int NOT NULL DEFAULT 24 COMMENT '开始前多少小时可退',
  `allow_refund_after_verified` tinyint NOT NULL DEFAULT 0 COMMENT '已核销是否可退',
  `auto_approve_refund` tinyint NOT NULL DEFAULT 0 COMMENT '是否自动通过退款',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
  `del_flag` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_scope` (`merchant_id`, `biz_type`, `status`, `del_flag`),
  KEY `idx_biz_type` (`biz_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3结算规则';

CREATE TABLE IF NOT EXISTS `refund_application` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `refund_no` varchar(64) DEFAULT NULL COMMENT '退款单号',
  `source_type` varchar(32) NOT NULL COMMENT '来源类型 registration/order/coupon',
  `source_id` bigint NOT NULL COMMENT '来源ID',
  `owner_type` bigint DEFAULT NULL COMMENT '拥有类型 1主题 2活动',
  `owner_id` bigint DEFAULT NULL COMMENT '主题/活动ID',
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `merchant_id` bigint DEFAULT NULL COMMENT '商家ID',
  `paid_amount` decimal(18,2) DEFAULT 0.00 COMMENT '支付金额',
  `refund_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '退款金额',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态 0待审核 1通过 2驳回',
  `reason` varchar(500) DEFAULT NULL COMMENT '申请原因',
  `reject_reason` varchar(500) DEFAULT NULL COMMENT '驳回/审核原因',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `adjustment_created` tinyint NOT NULL DEFAULT 0 COMMENT '是否已生成冲销',
  `idempotency_key` varchar(128) DEFAULT NULL COMMENT '幂等键',
  `del_flag` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_idempotency` (`idempotency_key`),
  KEY `idx_source` (`source_type`, `source_id`),
  KEY `idx_merchant_status` (`merchant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3退款申请';

INSERT INTO settlement_rule
(merchant_id, biz_type, rule_name, settlement_cycle, platform_commission_rate, merchant_rate, creator_rate, inviter_rate,
 min_settlement_amount, refund_deadline_hours, allow_refund_after_verified, auto_approve_refund, status, del_flag, create_time)
SELECT 0, 'activity', '平台默认活动结算规则', 'monthly', 0.1000, 0.9000, 0.0000, 0.0000, 100.00, 24, 0, 0, 1, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'activity' AND status = 1 AND del_flag = 0);

INSERT INTO settlement_rule
(merchant_id, biz_type, rule_name, settlement_cycle, platform_commission_rate, merchant_rate, creator_rate, inviter_rate,
 min_settlement_amount, refund_deadline_hours, allow_refund_after_verified, auto_approve_refund, status, del_flag, create_time)
SELECT 0, 'topic', '平台默认主题结算规则', 'monthly', 0.1000, 0.8000, 0.1000, 0.0000, 100.00, 24, 0, 0, 1, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'topic' AND status = 1 AND del_flag = 0);

INSERT INTO settlement_rule
(merchant_id, biz_type, rule_name, settlement_cycle, platform_commission_rate, merchant_rate, creator_rate, inviter_rate,
 min_settlement_amount, refund_deadline_hours, allow_refund_after_verified, auto_approve_refund, status, del_flag, create_time)
SELECT 0, 'coupon', '平台默认优惠券核销结算规则', 'monthly', 0.0000, 1.0000, 0.0000, 0.0000, 100.00, 24, 0, 0, 1, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'coupon' AND status = 1 AND del_flag = 0);

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '结算规则查询', 0, 6, '#', '', 1, 0, 'F', '0', '0', 'business:settlementrule:list', '#', 'system', NOW(), 'P3 Phase C settlement rule list permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlementrule:list');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '结算规则编辑', 0, 7, '#', '', 1, 0, 'F', '0', '0', 'business:settlementrule:edit', '#', 'system', NOW(), 'P3 Phase C settlement rule edit permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlementrule:edit');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '退款查询', 0, 8, '#', '', 1, 0, 'F', '0', '0', 'business:refund:list', '#', 'system', NOW(), 'P3 Phase C refund list permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:refund:list');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '退款审核', 0, 9, '#', '', 1, 0, 'F', '0', '0', 'business:refund:review', '#', 'system', NOW(), 'P3 Phase C refund review permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:refund:review');

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
SET menu_name = '结算规则',
    parent_id = @p3_parent,
    order_num = 9,
    path = 'settlementrule',
    component = 'business/settlementrule/index',
    query = '',
    route_name = '',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'C',
    visible = '0',
    status = '0',
    icon = 'build',
    remark = 'P3结算规则'
WHERE perms = 'business:settlementrule:list';

INSERT INTO sys_menu
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT '结算规则', @p3_parent, 9, 'settlementrule', 'business/settlementrule/index', '', '', 1, 0, 'C', '0', '0', 'business:settlementrule:list', 'build', 'admin', sysdate(), '', NULL, 'P3结算规则'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlementrule:list');

SET @settlement_rule_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:settlementrule:list' LIMIT 1
);

UPDATE sys_menu
SET parent_id = @settlement_rule_menu,
    path = '',
    component = '',
    menu_type = 'F',
    visible = '0',
    status = '0'
WHERE perms = 'business:settlementrule:edit';

UPDATE sys_menu
SET menu_name = '退款审核',
    parent_id = @p3_parent,
    order_num = 10,
    path = 'refund',
    component = 'business/refund/index',
    query = '',
    route_name = '',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'C',
    visible = '0',
    status = '0',
    icon = 'form',
    remark = 'P3退款审核'
WHERE perms = 'business:refund:list';

INSERT INTO sys_menu
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT '退款审核', @p3_parent, 10, 'refund', 'business/refund/index', '', '', 1, 0, 'C', '0', '0', 'business:refund:list', 'form', 'admin', sysdate(), '', NULL, 'P3退款审核'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:refund:list');

SET @refund_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:refund:list' LIMIT 1
);

UPDATE sys_menu
SET parent_id = @refund_menu,
    path = '',
    component = '',
    menu_type = 'F',
    visible = '0',
    status = '0'
WHERE perms = 'business:refund:review';
