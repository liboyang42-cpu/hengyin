-- Apply to the development/test database before deploying the matching backend.
-- Existing rows remain NULL; do not invent a fingerprint for historic requests.
ALTER TABLE npc_chat_request ADD COLUMN request_hash CHAR(64) NULL AFTER request_id;
