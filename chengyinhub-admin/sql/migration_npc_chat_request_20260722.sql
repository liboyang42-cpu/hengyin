-- Phase 3 漫游 NPC Chat requestId 幂等终态。
--
-- 只读预检（执行本迁移前单独运行，不在本文件里写生产开关）：
--   SELECT table_name FROM information_schema.tables
--   WHERE table_schema = DATABASE() AND table_name = 'npc_chat_request';
--
-- 设计边界：
--   1. 唯一键(user_id, request_id)把幂等严格限定在当前登录用户；同 requestId 的不同用户绝不重放。
--   2. active_slot 只在 PROCESSING 写 1，终态清为 NULL；MySQL 唯一键允许多个 NULL，
--      所以 uk(user_id, session_id, active_slot)只限制同一漫游会话的一条进行中请求。
--   3. safe_text 是可展示终态摘要；不把用户原文再复制到此表。
--   4. dataRetentionCleanupTask 以 create_time 为锚点在 48 小时后分批清理本表。
--
-- 注意：本迁移不修改 feature.flag.roamNpcEvent / feature.flag.roamNpcChat，生产默认仍为 false。

CREATE TABLE IF NOT EXISTS npc_chat_request (
  id                    BIGINT       NOT NULL AUTO_INCREMENT,
  user_id               BIGINT       NOT NULL,
  request_id            VARCHAR(64)  NOT NULL,
  session_id            BIGINT       NOT NULL,
  status                VARCHAR(16)  NOT NULL COMMENT 'PROCESSING/SUCCEEDED/REJECTED/FAILED',
  safety_decision       VARCHAR(16)  NOT NULL COMMENT 'PASS/BLOCKED/NOT_RUN',
  error_code            VARCHAR(64)  DEFAULT NULL,
  limit_type            VARCHAR(16)  DEFAULT NULL,
  retry_after_seconds   INT          DEFAULT NULL,
  retryable             TINYINT      NOT NULL DEFAULT 0,
  safe_text             VARCHAR(1000) DEFAULT NULL,
  ai                    TINYINT      NOT NULL DEFAULT 1,
  lease_token           VARCHAR(64)  DEFAULT NULL,
  lease_until           DATETIME     DEFAULT NULL,
  active_slot           TINYINT      DEFAULT NULL,
  completed_at          DATETIME     DEFAULT NULL,
  create_time           DATETIME     NOT NULL,
  update_time           DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_npc_chat_request_user_request (user_id, request_id),
  UNIQUE KEY uk_npc_chat_request_active_session (user_id, session_id, active_slot),
  KEY idx_npc_chat_request_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='漫游NPC Chat幂等终态（48小时）';

-- 迁移回读：表与两个安全边界索引都必须存在。
SELECT index_name
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'npc_chat_request'
  AND index_name IN ('uk_npc_chat_request_user_request', 'uk_npc_chat_request_active_session',
                     'idx_npc_chat_request_create_time')
ORDER BY index_name;
