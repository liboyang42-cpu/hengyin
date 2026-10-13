-- §6.1 退款失败人工兜底:refund_application 增打款结果与人工处理/双人复核/回执列。
-- 先执行本迁移,再部署引用新列的 jar。只新增可空列(payout_status 有默认值),不改存量数据。
-- 生产执行前必须先用 chengyin-db-migrate --describe refund_application 预检。
-- MySQL 8:用 information_schema 预检 + PREPARE 幂等,禁用 MariaDB 专属 ADD COLUMN IF NOT EXISTS。

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'refund_application'
                  AND column_name = 'payout_status');
SET @sql := IF(@exists = 0,
  'ALTER TABLE refund_application
     ADD COLUMN payout_status       TINYINT      NOT NULL DEFAULT 0 COMMENT ''打款结果:0处理中 1原路成功 2原路失败待人工 3人工已退待复核 4双人复核完成'',
     ADD COLUMN payout_method       VARCHAR(32)  NULL COMMENT ''人工退款方式:original_retry/offline_transfer/wx_abnormal_change/wx_abnormal_bankcard'',
     ADD COLUMN payout_receipt_url  VARCHAR(512) NULL COMMENT ''人工退款回执凭证URL'',
     ADD COLUMN payout_handler_id   BIGINT       NULL COMMENT ''人工处理人ID'',
     ADD COLUMN payout_handled_at   DATETIME     NULL COMMENT ''人工处理时间'',
     ADD COLUMN payout_reviewer2_id BIGINT       NULL COMMENT ''双人复核第二复核人ID(须≠处理人)'',
     ADD COLUMN payout_reviewed2_at DATETIME     NULL COMMENT ''双人复核时间'',
     ADD COLUMN payout_remark       VARCHAR(500) NULL COMMENT ''人工处理备注/原路失败原因''',
  'SELECT ''payout columns already exist, skip'' ');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 便于人工队列扫描:按 payout_status 建索引(存在则跳过)
SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'refund_application'
               AND index_name = 'idx_refund_payout_status');
SET @sql2 := IF(@idx = 0,
  'ALTER TABLE refund_application ADD INDEX idx_refund_payout_status (payout_status)',
  'SELECT ''idx exists, skip'' ');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
