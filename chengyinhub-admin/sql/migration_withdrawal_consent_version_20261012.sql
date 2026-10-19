-- F0425：银行卡提现申请固定建单时已校验的单独同意文本版本（bank_account_collection）。
-- 旧申请保持 NULL（建单时未绑定版本），不回填、不推断；本迁移不改变既有 status/金额/收款资料。
-- 必须先于引用 consent_version 的后端 jar 执行。生产执行前先确认下方预检返回 0 行。
SELECT column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ums_member_withdrawal'
  AND column_name = 'consent_version';

ALTER TABLE ums_member_withdrawal
    ADD COLUMN consent_version VARCHAR(32) NULL COMMENT '建单时校验通过的银行卡信息单独同意文本版本；旧单为空' AFTER payout_reference;
