-- 押金改造:锁价后应缴 + 开放报名溯源(2026-07-22)
-- 先走 chengyin-db-migrate 只读预检,确认 coop_invite 两列尚不存在后执行;本文件必须先于新 jar 落库。
-- origin_apply_id 仅记录「俱乐部先申请、商家再发 type1 邀约」的真实申请,用于锁价后反转应缴方。
-- deposit_status 扩展:0无需/1支付中/2已缴冻结/3已扣(结算抵扣或客服仲裁)/4已退/5应缴未缴。

ALTER TABLE coop_invite
    ADD COLUMN origin_apply_id BIGINT NULL COMMENT '开放报名溯源 coop_club_apply.id(type1 有值则发起方缴押金)' AFTER topic_id,
    ADD KEY idx_coop_origin_apply (origin_apply_id);

ALTER TABLE coop_invite
    MODIFY COLUMN deposit_status TINYINT(1) NULL DEFAULT 0 COMMENT '保证金状态 0无需/1支付中/2已缴冻结/3已扣/4已退/5应缴未缴';
