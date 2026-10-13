-- 提现敏感资料加密：先执行本迁移，再部署引用新列的 jar。
-- 只新增可空列，不在 SQL 中处理明文或加密密钥；历史回填由应用在
-- CHENGYIN_WITHDRAWAL_PII_KEY 与 CHENGYIN_WITHDRAWAL_PII_BACKFILL_LIMIT 受控时完成。
-- 生产执行前必须先用 chengyin-db-migrate --describe ums_member_withdrawal 预检。

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ums_member_withdrawal'
                  AND column_name = 'pii_cipher_version');
SET @sql := IF(@exists = 0,
  'ALTER TABLE ums_member_withdrawal
     ADD COLUMN realname_enc       VARCHAR(512) NULL COMMENT ''姓名AES-GCM密文'',
     ADD COLUMN bank_name_enc      VARCHAR(512) NULL COMMENT ''银行名称AES-GCM密文'',
     ADD COLUMN bank_account_enc   VARCHAR(512) NULL COMMENT ''银行卡AES-GCM密文'',
     ADD COLUMN mobilephone_enc    VARCHAR(512) NULL COMMENT ''手机号AES-GCM密文'',
     ADD COLUMN realname_mask      VARCHAR(32)  NULL COMMENT ''姓名掩码'',
     ADD COLUMN bank_name_mask     VARCHAR(32)  NULL COMMENT ''银行名称掩码'',
     ADD COLUMN bank_account_mask  VARCHAR(64)  NULL COMMENT ''银行卡掩码'',
     ADD COLUMN mobilephone_mask   VARCHAR(32)  NULL COMMENT ''手机号掩码'',
     ADD COLUMN pii_cipher_version TINYINT      NULL COMMENT ''敏感资料密文版本'',
     ADD COLUMN pii_migrated_at    DATETIME     NULL COMMENT ''历史明文清除完成时间''',
  'SELECT ''ums_member_withdrawal PII encryption columns already exist, skip''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回滚（仅确认没有数据写入新列后执行）：
-- ALTER TABLE ums_member_withdrawal
--   DROP COLUMN realname_enc, DROP COLUMN bank_name_enc, DROP COLUMN bank_account_enc,
--   DROP COLUMN mobilephone_enc, DROP COLUMN realname_mask, DROP COLUMN bank_name_mask,
--   DROP COLUMN bank_account_mask, DROP COLUMN mobilephone_mask, DROP COLUMN pii_cipher_version,
--   DROP COLUMN pii_migrated_at;
