-- C 包 forward migration：旧记录不回填意图，不自动重放无法确认的请求。
ALTER TABLE npc_chat_request
  ADD COLUMN intent_hash CHAR(64) NULL COMMENT '稳定意图 SHA-256' AFTER session_id,
  ADD COLUMN scope_type TINYINT NULL COMMENT 'NPC scope: 2 merchant, 3 global' AFTER intent_hash,
  ADD COLUMN scope_id BIGINT NULL COMMENT 'NPC profile id' AFTER scope_type,
  ADD COLUMN scene VARCHAR(32) NULL COMMENT '调用场景' AFTER scope_id,
  ADD COLUMN profile_version VARCHAR(64) NULL COMMENT '回放授权/人设版本' AFTER scene,
  ADD COLUMN provider_state VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED'
    COMMENT 'NOT_STARTED/STARTED' AFTER profile_version;
