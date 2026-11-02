-- 原请求与写入结果同事务保存；无 TTL，不释放未知请求身份。生产执行须另行授权。
CREATE TABLE IF NOT EXISTS app_author_write_receipt (
  actor_member_id BIGINT NOT NULL,
  operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  result_json LONGTEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (actor_member_id, operation, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'app_author_write_receipt';
