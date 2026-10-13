-- R9-35:平台停用商家原因独立成列,不再与审核驳回原因 reson 复用。
-- 背景(审查_审查B_安全与隐私.md §4 R9-35 P1):mms_merchant 只有审核驳回原因 reson,
--   停用(account_status=2)没有独立原因字段 ⇒ 小程序停用卡复用 reson,把上一次驳回
--   填的「执照模糊」当成停用原因展示。
-- 语义:disable_reason 只由平台「停用商家」入口写入;平台「启用」时清空;
--   审核通过/驳回只写 reson,永不写本列 —— 两者不复用。
-- 幂等守卫:以 disable_reason 为哨兵列,已存在则整段跳过(MySQL 8 无 ADD COLUMN IF NOT EXISTS),
--   避免重跑 1060 中断。
-- ⚠️ 只在库上跑:合入 master 前须走 chengyin-db-migrate 只读预检 + 用户确认后落库;本文件不自动执行。

SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'mms_merchant'
                 AND column_name = 'disable_reason');
SET @sql := IF(@exist = 0,
  'ALTER TABLE mms_merchant
     ADD COLUMN disable_reason VARCHAR(500) NULL COMMENT ''平台停用原因;只由平台停用入口写入,启用时清空'' AFTER reson',
  'SELECT ''mms_merchant.disable_reason 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'mms_merchant'
  AND column_name = 'disable_reason'
  AND column_type = 'varchar(500)'
  AND is_nullable = 'YES'
  AND column_default IS NULL
  AND character_set_name = 'utf8mb4';

-- 回滚(确认没有查询依赖后):
-- ALTER TABLE mms_merchant DROP COLUMN disable_reason;
