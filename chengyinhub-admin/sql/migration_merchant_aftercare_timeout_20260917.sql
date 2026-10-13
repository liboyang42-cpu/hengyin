-- 拍板 2026-09-16 #12:售后商家 24 小时未回应,自动转平台客服处理并通知玩家。
-- 背景(D_商家侧.md D-15 / E15):merchant_aftercare 只有人工队列,没有任何超时任务;
--   商家不回应时玩家只能干等。本迁移给退款单加一个只增不减的「已转平台」时间戳,
--   由 merchantAftercareTimeoutTask 每 10 分钟幂等扫描写入 + 给玩家发站内信。
--
-- 语义:platform_takeover_at 只由超时任务在「status=0 且商家无同意/驳回意见」时写入一次;
--   不改变退款审核 status、不改变打款 payout_status、不影响已售订单核销与履约。
-- 幂等守卫:以 platform_takeover_at 为哨兵列,已存在则整段跳过(MySQL 8 无 ADD COLUMN IF NOT EXISTS),
--   避免重跑 1060 中断。
-- ⚠️ 只在库上跑:合入 master 前须走 chengyin-db-migrate 只读预检 + 用户确认后落库;本文件不自动执行。

SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'refund_application'
                 AND column_name = 'platform_takeover_at');
SET @sql := IF(@exist = 0,
  'ALTER TABLE refund_application
     ADD COLUMN platform_takeover_at DATETIME NULL COMMENT ''商家售后超时自动转平台客服的处理时间;NULL=未转过(幂等判据)'' AFTER update_time',
  'SELECT ''refund_application.platform_takeover_at 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'refund_application'
  AND column_name = 'platform_takeover_at'
  AND column_type = 'datetime'
  AND is_nullable = 'YES'
  AND column_default IS NULL;

-- 回滚(确认没有查询依赖后):
-- ALTER TABLE refund_application DROP COLUMN platform_takeover_at;
