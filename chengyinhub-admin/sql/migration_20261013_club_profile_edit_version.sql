-- F0498: 先应用本迁移，再部署引用 profile_version / 保存回执的新后端。
-- 只供审阅与专属测试库执行；生产迁移仍需另行授权。旧编辑请求继续允许无条件写，但也推进版本。
SET @exist := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'club' AND column_name = 'profile_version');
SET @sql := IF(@exist = 0,
  'ALTER TABLE club ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 1 COMMENT ''资料编辑版本''',
  'SELECT ''club.profile_version 已存在''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回执与资料写在同一事务内；不保留资料文本，只保留请求摘要和原结果。
CREATE TABLE IF NOT EXISTS club_profile_edit_receipt (
  club_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  result_json TEXT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (club_id, actor_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'club' AND column_name = 'profile_version'
  AND data_type = 'bigint' AND is_nullable = 'NO';
