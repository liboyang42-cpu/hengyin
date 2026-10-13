-- 合规同意留痕。仅新增表；生产执行前必须使用 chengyin-db-migrate 预检并取得确认。
CREATE TABLE IF NOT EXISTS consent_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  doc_type VARCHAR(32) NOT NULL,
  doc_version VARCHAR(32) NOT NULL,
  scene VARCHAR(32) NOT NULL,
  event_type VARCHAR(16) NOT NULL COMMENT 'AGREE/REVOKE',
  occurred_at DATETIME NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  ip_address VARCHAR(64) NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_consent_request (request_id),
  KEY idx_consent_member_doc_scene (member_id, doc_type, scene, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人信息同意与撤回事件留痕';
