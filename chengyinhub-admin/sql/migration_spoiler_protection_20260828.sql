-- NF-39 玩家剧透偏好与逐内容揭晓事实。仅生成迁移文件，禁止在本任务执行。
CREATE TABLE member_spoiler_preference (
  member_id BIGINT NOT NULL COMMENT 'ums_member.id',
  reveal_all TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=用户主动选择全部揭晓',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家剧透保护偏好';

CREATE TABLE member_spoiler_reveal (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  content_type VARCHAR(16) NOT NULL COMMENT '当前仅 NODE',
  content_id BIGINT NOT NULL,
  request_id VARCHAR(64) NOT NULL COMMENT '客户端稳定请求键，禁止随机回填',
  revealed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_spoiler_content (member_id, content_type, content_id),
  UNIQUE KEY uk_spoiler_request (member_id, request_id),
  KEY idx_spoiler_content (content_type, content_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家主动揭晓内容事实';

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'member_spoiler_reveal'
     AND column_name = 'request_id' AND is_nullable = 'NO') = 1
  AND EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'member_spoiler_reveal'
      AND index_name = 'uk_spoiler_content' AND non_unique = 0)
  AND EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'member_spoiler_reveal'
      AND index_name = 'uk_spoiler_request' AND non_unique = 0)
  AS assertion_ok;
