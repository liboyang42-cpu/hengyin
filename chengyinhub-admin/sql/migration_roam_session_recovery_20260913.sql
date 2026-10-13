-- 同设备漫游精确恢复。只为新会话建立关联；旧 NULL 会话不猜测回填。
ALTER TABLE roam_session
    ADD COLUMN client_session_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '玩家作用域客户端会话关联',
    ADD COLUMN finish_result_json MEDIUMTEXT NULL COMMENT '同结算事务保存的原响应结果',
    ADD UNIQUE KEY uk_roam_member_client_session (member_id, client_session_key);
