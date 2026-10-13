-- B 包测试/待部署迁移：未执行生产 DDL；业务意图和报名使用同一数据库事务。
CREATE TABLE IF NOT EXISTS im_notification_intent (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recipient BIGINT NOT NULL,
    msg_type INT NOT NULL,
    content TEXT,
    extra_json TEXT,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    message_id BIGINT,
    create_time DATETIME NOT NULL,
    delivered_at DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_im_notification_event (event_key),
    KEY idx_im_notification_pending (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 新任务默认暂停；迁移、同版本事务/双worker回读和业务联调完成后由用户启用。
INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
     create_by, create_time, remark)
SELECT '原站内通知意图投递', 'DEFAULT', 'imNotificationDeliveryTask.deliverPending()',
       '0 0/1 * * * ?', '3', '1', '1', 'system', NOW(),
       '默认暂停；仅原站内消息，不投外部渠道；待测试库事务和原事件回读验证后启用'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target = 'imNotificationDeliveryTask.deliverPending()'
);
