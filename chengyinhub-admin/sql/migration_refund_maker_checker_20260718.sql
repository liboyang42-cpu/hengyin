-- A1-1(P0)退款 maker≠checker:refund_application 增「后台建单人」列。
-- 用途:reviewRefund 批准(status=1,触发真金打款)前,校验审批人 ≠ 建单人,
--       堵「单个后台账号凭空造单 + 自审自批 + 触发微信原路退款」这条资金外流通道。
-- 只新增一个可空列,不改存量数据;存量行 created_by_user_id 为 NULL ⇒ 视为非后台建单,不受双人闸约束。
-- ★先执行本迁移,再部署引用新列的 jar(否则 insert/select 报 Unknown column)。
-- 生产执行前必须先用 chengyin-db-migrate --describe refund_application 预检。
-- MySQL 8:用 information_schema 预检 + PREPARE 幂等,禁用 MariaDB 专属 ADD COLUMN IF NOT EXISTS。

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'refund_application'
                  AND column_name = 'created_by_user_id');
SET @sql := IF(@exists = 0,
  'ALTER TABLE refund_application
     ADD COLUMN created_by_user_id BIGINT NULL COMMENT ''后台建单人sys_user_id(maker≠checker用;C端/系统发起为NULL)''',
  'SELECT ''created_by_user_id already exists, skip'' ');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
