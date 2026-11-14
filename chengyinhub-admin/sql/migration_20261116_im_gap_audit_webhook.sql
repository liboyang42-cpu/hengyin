-- 频道缺口包（App BeGapIm：/api/im/audit-log 与 /api/im/webhook/*）。
-- 只新增表、放宽回执族约束（新增 HOOK = Webhook 管理写原回执）；不回填、不改既有行；本地/测试库候选，不在生产执行。
-- 依赖：20261115_im_channels_fourth_pass（回执族已含 BLOCK）、20261113_im_channels_second_pass（im_moderation_action）、
--       20261105_l8_channel_lifecycle（im_channel / im_channel_op_log）。

SET @ck_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'im_channel_request_receipt'
       AND constraint_name = 'ck_channel_receipt_family' AND constraint_type = 'CHECK'
);
SET @ck_ready = (
    SELECT COUNT(*) FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_channel_receipt_family'
       AND check_clause LIKE '%''HOOK''%'
);
SET @ddl = IF(
    @ck_exists > 0 AND @ck_ready = 0,
    'ALTER TABLE im_channel_request_receipt DROP CHECK ck_channel_receipt_family',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ck_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'im_channel_request_receipt'
       AND constraint_name = 'ck_channel_receipt_family' AND constraint_type = 'CHECK'
);
SET @ddl = IF(
    @ck_exists = 0,
    'ALTER TABLE im_channel_request_receipt ADD CONSTRAINT ck_channel_receipt_family CHECK (family IN (''META'',''GOV'',''MSG'',''FRIEND'',''FMETA'',''FREMOVE'',''FINVITE'',''FTEAM'',''MOD'',''APPEAL'',''REPORT'',''NOTIFY'',''CHAIN'',''CHECKIN'',''ADMIT'',''STATE'',''PRIVACY'',''ROLE'',''MEDIA'',''ONBOARD'',''BLOCK'',''HOOK''))',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 1) 空间审计投影：原始来源是 im_channel_op_log 与 im_moderation_action（含撤销），每条原始事件投影一次，
--    按空间串成 SHA-256 链（prev_hash → row_hash）。只投影已存在的原始行，不推断历史，不声称外部锚定。
CREATE TABLE IF NOT EXISTS im_channel_audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    guild_kind TINYINT NOT NULL,
    guild_id BIGINT UNSIGNED NOT NULL,
    channel_id BIGINT UNSIGNED NOT NULL,
    source VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'CHANNEL_OP/MODERATION/MODERATION_REVOKE',
    original_id BIGINT UNSIGNED NOT NULL,
    actor_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_member_id BIGINT NULL,
    action VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id BIGINT UNSIGNED NULL,
    reason VARCHAR(500) NULL,
    changes_json TEXT NULL,
    changes_redacted TINYINT NOT NULL DEFAULT 0,
    original_time DATETIME(3) NOT NULL,
    prev_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_original (source, original_id),
    KEY idx_audit_guild (guild_kind, guild_id, id),
    CONSTRAINT ck_audit_source CHECK (source IN ('CHANNEL_OP','MODERATION','MODERATION_REVOKE')),
    CONSTRAINT ck_audit_actor CHECK (actor_type IN ('MEMBER','SYSTEM')),
    CONSTRAINT ck_audit_redacted CHECK (changes_redacted IN (0,1)),
    CONSTRAINT ck_audit_changes CHECK (changes_json IS NULL OR JSON_VALID(changes_json)),
    CONSTRAINT ck_audit_hash CHECK (prev_hash REGEXP '^[0-9a-f]{64}$' AND row_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='空间审计投影（哈希链）';

-- 每个空间一行链头；投影追加时行锁串行化。
CREATE TABLE IF NOT EXISTS im_channel_audit_head (
    guild_kind TINYINT NOT NULL,
    guild_id BIGINT UNSIGNED NOT NULL,
    head_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    head_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (guild_kind, guild_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='空间审计链头';

-- 2) 出站 Webhook 订阅：普通读只给 url_host；签名密钥只存 SHA-256 与前缀，明文仅在创建/轮换响应里出现一次。
CREATE TABLE IF NOT EXISTS im_webhook_subscription (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT UNSIGNED NOT NULL,
    channel_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(64) NOT NULL,
    url VARCHAR(512) NOT NULL,
    url_host VARCHAR(255) NOT NULL,
    events_json VARCHAR(2048) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    deleted TINYINT NOT NULL DEFAULT 0,
    revision INT NOT NULL DEFAULT 1,
    key_version INT NOT NULL DEFAULT 1,
    secret_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    secret_prefix VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fail_count BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(255) NULL,
    last_delivery_at DATETIME(3) NULL,
    created_by BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_webhook_sub_scope (owner_type, owner_id, channel_id, deleted, id),
    CONSTRAINT ck_webhook_sub_owner CHECK (owner_type IN ('CLUB')),
    CONSTRAINT ck_webhook_sub_status CHECK (status IN (0,1) AND deleted IN (0,1) AND (deleted = 0 OR status = 0)),
    CONSTRAINT ck_webhook_sub_versions CHECK (revision > 0 AND key_version > 0),
    CONSTRAINT ck_webhook_sub_events CHECK (JSON_VALID(events_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道出站 Webhook 订阅';

-- 投递记录：本批没有出站投递器，测试/重试只落 PENDING，不声称已发出。
CREATE TABLE IF NOT EXISTS im_webhook_delivery (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subscription_id BIGINT UNSIGNED NOT NULL,
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    reason VARCHAR(255) NULL,
    response_snippet VARCHAR(512) NULL,
    http_status INT NULL,
    latency_ms BIGINT NULL,
    next_retry_at DATETIME(3) NULL,
    payload_json TEXT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_webhook_delivery_event (subscription_id, event_id),
    KEY idx_webhook_delivery_sub (subscription_id, state, id),
    CONSTRAINT ck_webhook_delivery_state CHECK (state IN ('PENDING','INFLIGHT','SENT','FAILED','DEAD','UNKNOWN','CANCELLED')),
    CONSTRAINT ck_webhook_delivery_attempt CHECK (attempt >= 0),
    CONSTRAINT ck_webhook_delivery_payload CHECK (payload_json IS NULL OR JSON_VALID(payload_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道出站 Webhook 投递';

-- 3) 入站 Webhook 端点：本批不开放入站写入（创建返回 WEBHOOK_INBOUND_UNAVAILABLE）；表与读路由先就位。
CREATE TABLE IF NOT EXISTS im_webhook_endpoint (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT UNSIGNED NOT NULL,
    channel_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(64) NOT NULL,
    avatar_url VARCHAR(512) NULL,
    status TINYINT NOT NULL DEFAULT 1,
    deleted TINYINT NOT NULL DEFAULT 0,
    revision INT NOT NULL DEFAULT 1,
    key_version INT NOT NULL DEFAULT 1,
    token_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_prefix VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rate_limit_per_min INT NOT NULL DEFAULT 30,
    allowed_ips_json VARCHAR(2048) NOT NULL DEFAULT '[]',
    last_called_at DATETIME(3) NULL,
    created_by BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_webhook_ep_scope (owner_type, owner_id, channel_id, deleted, id),
    CONSTRAINT ck_webhook_ep_owner CHECK (owner_type IN ('CLUB')),
    CONSTRAINT ck_webhook_ep_status CHECK (status IN (0,1) AND deleted IN (0,1) AND (deleted = 0 OR status = 0)),
    CONSTRAINT ck_webhook_ep_rate CHECK (rate_limit_per_min BETWEEN 1 AND 300),
    CONSTRAINT ck_webhook_ep_ips CHECK (JSON_VALID(allowed_ips_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道入站 Webhook 端点';

CREATE TABLE IF NOT EXISTS im_webhook_inbound_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    endpoint_id BIGINT UNSIGNED NOT NULL,
    result VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(255) NULL,
    message_id BIGINT UNSIGNED NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    nonce VARCHAR(64) NULL,
    sender_type TINYINT NULL,
    webhook_id BIGINT UNSIGNED NULL,
    original_message_stored TINYINT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_webhook_inbound_ep (endpoint_id, result, id),
    CONSTRAINT ck_webhook_inbound_result CHECK (result IN ('RECEIVED','PENDING_REVIEW','ACCEPTED','REJECTED','BLOCKED','RATE_LIMITED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道入站 Webhook 调用日志';
