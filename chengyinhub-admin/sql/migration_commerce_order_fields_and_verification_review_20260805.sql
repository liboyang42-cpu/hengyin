-- T6 商业化订单字段 + 核销异常人工复核队列。
-- 合并前需先落库；本迁移不启用收费，也不执行任何自动打款。

ALTER TABLE oms_order
    ADD COLUMN biz_type VARCHAR(50) NULL COMMENT '商业化订单类型:premium_template/promotion_slot/custom_event' AFTER order_type,
    ADD COLUMN entitlement_max_usage INT NULL COMMENT '下单时冻结的权益次数' AFTER biz_type,
    ADD COLUMN entitlement_days INT NULL COMMENT '下单时冻结的有效天数' AFTER entitlement_max_usage,
    ADD COLUMN payment_transaction_id VARCHAR(64) NULL COMMENT '微信支付交易号,用于回调对账' AFTER entitlement_days,
    ADD KEY idx_oms_order_biz_sn (biz_type, order_sn);

ALTER TABLE settlement_rule
    ADD COLUMN unit_price DECIMAL(18,2) NULL COMMENT '商业化后台配置单价;NULL=不可自助售卖' AFTER guarantee_max_merchant,
    ADD COLUMN entitlement_max_usage INT NULL COMMENT '购买一次授予的次数' AFTER unit_price,
    ADD COLUMN entitlement_days INT NULL COMMENT '购买一次授予的有效天数' AFTER entitlement_max_usage;

-- 三条仅是关闭态配置槽位：价格/次数/有效期均留空，自助收银台配置仍默认 false。
-- 平台/商家比例是后台可编辑字段，不参与当前人工议价与对公结算。
INSERT INTO settlement_rule
(merchant_id, biz_type, rule_name, settlement_cycle, platform_commission_rate, merchant_rate,
 creator_rate, inviter_rate, unit_price, entitlement_max_usage, entitlement_days,
 min_settlement_amount, refund_deadline_hours, allow_refund_after_verified,
 auto_approve_refund, status, del_flag, create_time)
SELECT 0, 'premium_template', '高级模板商业化配置', 'manual', 0, 1, 0, 0,
       NULL, NULL, NULL, 0, 0, 0, 0, 0, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM settlement_rule
                  WHERE merchant_id=0 AND biz_type='premium_template' AND del_flag=0);

INSERT INTO settlement_rule
(merchant_id, biz_type, rule_name, settlement_cycle, platform_commission_rate, merchant_rate,
 creator_rate, inviter_rate, unit_price, entitlement_max_usage, entitlement_days,
 min_settlement_amount, refund_deadline_hours, allow_refund_after_verified,
 auto_approve_refund, status, del_flag, create_time)
SELECT 0, 'promotion_slot', '推广位商业化配置', 'manual', 0, 1, 0, 0,
       NULL, NULL, NULL, 0, 0, 0, 0, 0, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM settlement_rule
                  WHERE merchant_id=0 AND biz_type='promotion_slot' AND del_flag=0);

INSERT INTO settlement_rule
(merchant_id, biz_type, rule_name, settlement_cycle, platform_commission_rate, merchant_rate,
 creator_rate, inviter_rate, unit_price, entitlement_max_usage, entitlement_days,
 min_settlement_amount, refund_deadline_hours, allow_refund_after_verified,
 auto_approve_refund, status, del_flag, create_time)
SELECT 0, 'custom_event', '活动定制商业化配置', 'manual', 0, 1, 0, 0,
       NULL, NULL, NULL, 0, 0, 0, 0, 0, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM settlement_rule
                  WHERE merchant_id=0 AND biz_type='custom_event' AND del_flag=0);

CREATE TABLE verification_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    verifier_member_id BIGINT NOT NULL COMMENT '被抽检的核销负责人memberId',
    topic_id BIGINT NOT NULL,
    risk_type VARCHAR(32) NOT NULL COMMENT 'HIGH_RATE/BURST/HIGH_RATE_AND_BURST',
    verification_rate DECIMAL(10,4) NOT NULL,
    paid_count INT NOT NULL,
    verified_count INT NOT NULL,
    one_minute_count INT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待复核 1已确认正常 2确认异常',
    idempotency_key VARCHAR(128) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_verification_review_idempotency (idempotency_key),
    KEY idx_verification_review_queue (status, create_time),
    KEY idx_verification_review_topic (topic_id, verifier_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='核销率与频次异常人工复核队列';

INSERT INTO sys_menu
(menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
 visible, status, perms, icon, create_by, create_time, remark)
SELECT '核销异常复核', 0, 62, '#', '', 1, 0, 'F', '0', '0',
       'business:verificationReview:list', '#', 'admin', NOW(), '只读人工复核队列'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:verificationReview:list');
