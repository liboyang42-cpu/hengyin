-- 自由探索完成后 24-72 小时召回。
-- 合并前需先落库；任务初始暂停且代码开关默认关闭，公众平台创建订阅模板并配置模板 ID 后再开启。

SET @has_recall_sent_at := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roam_session' AND COLUMN_NAME = 'recall_sent_at'
);
SET @ddl := IF(@has_recall_sent_at = 0,
    'ALTER TABLE roam_session ADD COLUMN recall_sent_at DATETIME NULL COMMENT ''召回消息发送时间'' AFTER finished_at',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_recall_attempts := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roam_session' AND COLUMN_NAME = 'recall_attempts'
);
SET @ddl := IF(@has_recall_attempts = 0,
    'ALTER TABLE roam_session ADD COLUMN recall_attempts TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''召回尝试次数'' AFTER recall_sent_at',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_recall_last_attempt_at := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roam_session' AND COLUMN_NAME = 'recall_last_attempt_at'
);
SET @ddl := IF(@has_recall_last_attempt_at = 0,
    'ALTER TABLE roam_session ADD COLUMN recall_last_attempt_at DATETIME NULL COMMENT ''最近召回尝试时间'' AFTER recall_attempts',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_recall_index := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roam_session' AND INDEX_NAME = 'idx_roam_session_recall'
);
SET @ddl := IF(@has_recall_index = 0,
    'ALTER TABLE roam_session ADD INDEX idx_roam_session_recall (status, recall_sent_at, finished_at, recall_attempts, recall_last_attempt_at)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
     create_by, create_time, remark)
SELECT '漫游完成召回', 'DEFAULT', 'roamRecallTask.run()', '0 0/30 * * * ?', '3', '1', '1',
       'system', NOW(),
       '暂停；触发时机:jar部署且公众平台创建tplRoamRecall、同步小程序模板ID并开启CHENGYIN_ROAM_RECALL_ENABLED后启用。负责人:Arlan；到期:2026-08-31'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target = 'roamRecallTask.run()'
);

-- 落库后只读核验：
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
-- FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roam_session'
--   AND COLUMN_NAME IN ('recall_sent_at', 'recall_attempts', 'recall_last_attempt_at');
-- SELECT job_name, invoke_target, cron_expression, concurrent, status, remark
-- FROM sys_job WHERE invoke_target = 'roamRecallTask.run()';
