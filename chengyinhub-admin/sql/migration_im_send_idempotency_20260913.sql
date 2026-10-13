-- 同一发送者在同一会话内的逻辑消息标识。旧消息保持 NULL，不回填、不合并历史正文。
-- 本地候选；上线前须单独迁移验收，不在本次修复执行生产 SQL。
ALTER TABLE im_message
    ADD COLUMN client_message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '一次发送标识，重试不变',
    ADD COLUMN request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原请求摘要，防同标识改内容',
    ADD UNIQUE KEY uk_im_message_client (conversation_id, sender_id, client_message_id);
