-- 提现审核通过后增加“已打款”终态(status=3)，并保存银行流水号/打款凭据。
-- 生产执行前先确认下方预检返回 0 行；本迁移不改变既有 status=0/1/2 数据。
SELECT column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ums_member_withdrawal'
  AND column_name = 'payout_reference';

ALTER TABLE ums_member_withdrawal
    ADD COLUMN payout_reference VARCHAR(128) NULL COMMENT '银行流水号/打款凭据；status=3 时必填' AFTER status;
