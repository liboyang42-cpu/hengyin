-- 频道 Webhook 第二步（App NativeWebhook*）：出站投递器 + 入站接收。
-- 1) 签名密钥可恢复：im_webhook_subscription / im_webhook_endpoint 加 secret_cipher，存 PiiCryptoService（AES-256-GCM，"v1:" 前缀）
--    密文；SHA-256 与前缀列不变。此前建的订阅没有密文，投递器把它们的投递落 DEAD（SECRET_UNRECOVERABLE），轮换密钥后恢复。
-- 2) 投递租约：im_webhook_delivery 加 lease_until / last_attempt_at，与按到期时间取件的索引；状态集合不变（DEAD = 死信）。
-- 3) 入站防重放：im_webhook_inbound_nonce（端点 + nonce 主键），入站日志加按时间的限流索引。
-- 4) 投递扇出：按频道取订阅的索引。
-- 可重跑：每步先查 information_schema。只加列/索引/表，不改既有数据。
SET @hook_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='im_webhook_subscription' AND column_name='secret_cipher')=0,
 'ALTER TABLE im_webhook_subscription ADD COLUMN secret_cipher VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER secret_prefix', 'SELECT 1');
PREPARE hook_stmt FROM @hook_ddl;
EXECUTE hook_stmt;
DEALLOCATE PREPARE hook_stmt;
SET @hook_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='im_webhook_subscription' AND index_name='idx_webhook_sub_channel')=0,
 'ALTER TABLE im_webhook_subscription ADD KEY idx_webhook_sub_channel (channel_id, deleted, status, id)', 'SELECT 1');
PREPARE hook_stmt FROM @hook_ddl;
EXECUTE hook_stmt;
DEALLOCATE PREPARE hook_stmt;
SET @hook_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='im_webhook_endpoint' AND column_name='secret_cipher')=0,
 'ALTER TABLE im_webhook_endpoint ADD COLUMN secret_cipher VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER token_prefix', 'SELECT 1');
PREPARE hook_stmt FROM @hook_ddl;
EXECUTE hook_stmt;
DEALLOCATE PREPARE hook_stmt;
SET @hook_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='im_webhook_delivery' AND column_name='lease_until')=0,
 'ALTER TABLE im_webhook_delivery ADD COLUMN lease_until DATETIME(3) NULL AFTER next_retry_at', 'SELECT 1');
PREPARE hook_stmt FROM @hook_ddl;
EXECUTE hook_stmt;
DEALLOCATE PREPARE hook_stmt;
SET @hook_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='im_webhook_delivery' AND column_name='last_attempt_at')=0,
 'ALTER TABLE im_webhook_delivery ADD COLUMN last_attempt_at DATETIME(3) NULL AFTER lease_until', 'SELECT 1');
PREPARE hook_stmt FROM @hook_ddl;
EXECUTE hook_stmt;
DEALLOCATE PREPARE hook_stmt;
SET @hook_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='im_webhook_delivery' AND index_name='idx_webhook_delivery_due')=0,
 'ALTER TABLE im_webhook_delivery ADD KEY idx_webhook_delivery_due (state, next_retry_at, id)', 'SELECT 1');
PREPARE hook_stmt FROM @hook_ddl;
EXECUTE hook_stmt;
DEALLOCATE PREPARE hook_stmt;
SET @hook_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='im_webhook_inbound_log' AND index_name='idx_webhook_inbound_time')=0,
 'ALTER TABLE im_webhook_inbound_log ADD KEY idx_webhook_inbound_time (endpoint_id, create_time)', 'SELECT 1');
PREPARE hook_stmt FROM @hook_ddl;
EXECUTE hook_stmt;
DEALLOCATE PREPARE hook_stmt;

CREATE TABLE IF NOT EXISTS im_webhook_inbound_nonce (
    endpoint_id BIGINT UNSIGNED NOT NULL,
    nonce VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    signed_at BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (endpoint_id, nonce),
    KEY idx_webhook_nonce_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道入站 Webhook 已用 nonce（防重放）';
