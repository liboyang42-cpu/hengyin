-- D 补齐：通知原事件/接收人/渠道持久意图与 CAS 收据。
CREATE TABLE IF NOT EXISTS notification_delivery_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_type VARCHAR(32) NOT NULL,
  event_id BIGINT NOT NULL,
  recipient_id BIGINT NOT NULL,
  channel VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL COMMENT 'PENDING/PROCESSING/IN_APP_STORED/CHANNEL_ACCEPTED/UNKNOWN/FAILED',
  provider_code VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_notify_intent (event_type, event_id, recipient_id, channel),
  KEY idx_notify_status (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知意图与渠道收据';
