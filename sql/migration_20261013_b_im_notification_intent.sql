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
