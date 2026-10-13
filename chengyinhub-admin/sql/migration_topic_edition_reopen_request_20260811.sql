-- REOPEN_NEXT_EDITION 的幂等请求占位；只准备迁移，禁止直接在生产库执行。
-- MySQL 8：不使用 IF NOT EXISTS，执行前请先只读确认表不存在。
-- SELECT table_name FROM information_schema.tables
--  WHERE table_schema = DATABASE() AND table_name = 'topic_edition_reopen_request';

CREATE TABLE topic_edition_reopen_request (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    source_topic_id BIGINT NOT NULL COMMENT '来源 cms_topic.id',
    idempotency_key VARCHAR(64) NOT NULL COMMENT 'REOPEN_NEXT_EDITION 幂等键',
    new_topic_id BIGINT NULL COMMENT '成功创建后同事务回填的新 cms_topic.id',
    create_by VARCHAR(64) DEFAULT '' COMMENT '创建者',
    create_time DATETIME DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reopen (source_topic_id, idempotency_key),
    KEY idx_reopen_new_topic_id (new_topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主题再开一期请求幂等映射';

-- 执行后只读回查：同一 (source_topic_id, idempotency_key) 至多一行，new_topic_id 成功后应非空。
-- SELECT source_topic_id, idempotency_key, new_topic_id, create_by, create_time
--   FROM topic_edition_reopen_request ORDER BY id DESC LIMIT 20;
