-- C1: 统一提现链路——钱包提现补审计缺口(memberwithdrawal 范式)
-- 在 member_cash_withdraw 表加 audit_by / audit_time 列
-- ③类迁移:合前先落库，DDL 先于 jar 部署
ALTER TABLE member_cash_withdraw
    ADD COLUMN audit_by BIGINT NULL COMMENT '审计操作人ID(自助=memberId,人工=operatorId,自动=0)',
    ADD COLUMN audit_time DATETIME NULL COMMENT '审计操作时间';
