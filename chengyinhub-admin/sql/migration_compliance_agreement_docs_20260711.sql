-- 协议文档版本化。可安全重复执行；正式文案必须经法务审批后新建版本并切换 status。
CREATE TABLE IF NOT EXISTS agreement_doc (
    id BIGINT NOT NULL AUTO_INCREMENT,
    doc_type VARCHAR(32) NOT NULL COMMENT 'privacy/user_agreement',
    version VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    content LONGTEXT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0草稿 1生效',
    publish_time DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agreement_doc_type_version (doc_type, version),
    KEY idx_agreement_doc_active (doc_type, status, publish_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协议文档版本';

INSERT INTO agreement_doc (doc_type, version, title, content, status, publish_time)
SELECT 'privacy', 'v1.0-draft', '隐私政策', '待法务审批后替换为正式隐私政策全文。', 0, NULL
WHERE NOT EXISTS (SELECT 1 FROM agreement_doc WHERE doc_type = 'privacy');

INSERT INTO agreement_doc (doc_type, version, title, content, status, publish_time)
SELECT 'user_agreement', 'v1.0-draft', '用户服务协议', '待法务审批后替换为正式用户服务协议全文。', 0, NULL
WHERE NOT EXISTS (SELECT 1 FROM agreement_doc WHERE doc_type = 'user_agreement');
