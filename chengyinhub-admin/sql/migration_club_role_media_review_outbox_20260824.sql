-- ClubPost 编辑图片 durable outbox；不持久化原始 URL，只保存 SHA-256。
-- 阶段：pre-deploy schema；本文件只建表并回读结构，不注册依赖新 jar 的 Quartz 任务。
-- 前置：migration_club_post_announcement_20260823.sql（post.version）、
--       migration_wx_content_callback_inbox_20260724.sql（微信回调回执）。
-- 仅交付迁移；禁止在本任务执行数据库写入。上线仍须走 chengyin-db-migrate 预检/授权/读回。

CREATE TABLE club_post_media_review_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  post_version INT NOT NULL,
  submitter_member_id BIGINT NOT NULL,
  media_hash BINARY(32) NOT NULL COMMENT '单图 URL SHA-256；不保存原 URL',
  state TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 pending,1 processing,2 submitted,3 retryable,4 stale,5 poisoned',
  owner_token BINARY(16) DEFAULT NULL,
  fence_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
  lease_until DATETIME(3) DEFAULT NULL,
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  max_attempts INT UNSIGNED NOT NULL DEFAULT 5 COMMENT '告警阈值；瞬时外部故障超过阈值仍封顶退避重试',
  next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  trace_hash BINARY(32) DEFAULT NULL COMMENT '微信 trace_id SHA-256；SUBMITTED 必填',
  last_error_code VARCHAR(64) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_post_media_review_identity (post_id, post_version, media_hash),
  UNIQUE KEY uk_club_post_media_review_trace (trace_hash),
  KEY idx_club_post_media_review_due (state, next_attempt_at, lease_until, id),
  KEY idx_club_post_media_review_lease (state, lease_until, id),
  KEY idx_club_post_media_review_backlog (state, create_time, id),
  CONSTRAINT chk_club_post_media_review_state CHECK (state IN (0,1,2,3,4,5)),
  CONSTRAINT chk_club_post_media_review_attempt CHECK
    (post_version >= 0 AND attempt_count >= 0 AND max_attempts BETWEEN 1 AND 20),
  CONSTRAINT chk_club_post_media_review_owner CHECK
    (IF(state = 1, owner_token IS NOT NULL, owner_token IS NULL) = 1),
  CONSTRAINT chk_club_post_media_review_lease CHECK
    (IF(state = 1, lease_until IS NOT NULL, lease_until IS NULL) = 1),
  CONSTRAINT chk_club_post_media_review_trace CHECK
    (IF(state = 2, trace_hash IS NOT NULL, trace_hash IS NULL) = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ClubPost 单图微信内容安全送审 durable outbox';

-- 先逐列回读，禁止额外列、错误 nullable/default/auto_increment/on-update 假绿。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'club_post_media_review_outbox'
          AND engine = 'InnoDB') = 1
  AND COUNT(*) = 16
  AND SUM(CASE
    WHEN column_name = 'id' AND column_type = 'bigint' AND is_nullable = 'NO'
      AND column_default IS NULL AND LOWER(extra) = 'auto_increment' THEN 1
    WHEN column_name = 'post_id' AND column_type = 'bigint' AND is_nullable = 'NO'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'post_version' AND column_type = 'int' AND is_nullable = 'NO'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'submitter_member_id' AND column_type = 'bigint' AND is_nullable = 'NO'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'media_hash' AND column_type = 'binary(32)' AND is_nullable = 'NO'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'state' AND column_type = 'tinyint unsigned' AND is_nullable = 'NO'
      AND column_default <=> '0' THEN 1
    WHEN column_name = 'owner_token' AND column_type = 'binary(16)' AND is_nullable = 'YES'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'fence_token' AND column_type = 'bigint unsigned' AND is_nullable = 'NO'
      AND column_default <=> '0' THEN 1
    WHEN column_name = 'lease_until' AND column_type = 'datetime(3)' AND is_nullable = 'YES'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'attempt_count' AND column_type = 'int unsigned' AND is_nullable = 'NO'
      AND column_default <=> '0' THEN 1
    WHEN column_name = 'max_attempts' AND column_type = 'int unsigned' AND is_nullable = 'NO'
      AND column_default <=> '5' THEN 1
    WHEN column_name = 'next_attempt_at' AND column_type = 'datetime(3)' AND is_nullable = 'NO'
      AND LOWER(column_default) <=> 'current_timestamp(3)' THEN 1
    WHEN column_name = 'trace_hash' AND column_type = 'binary(32)' AND is_nullable = 'YES'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'last_error_code' AND column_type = 'varchar(64)' AND is_nullable = 'YES'
      AND column_default IS NULL THEN 1
    WHEN column_name = 'create_time' AND column_type = 'datetime(3)' AND is_nullable = 'NO'
      AND LOWER(column_default) <=> 'current_timestamp(3)' THEN 1
    WHEN column_name = 'update_time' AND column_type = 'datetime(3)' AND is_nullable = 'NO'
      AND LOWER(column_default) <=> 'current_timestamp(3)'
      AND LOWER(extra) LIKE '%on update current_timestamp(3)%' THEN 1
    ELSE 0 END) = 16 AS assertion_ok
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'club_post_media_review_outbox';

-- 六个 BTREE 索引逐项回读：唯一性、可见性、完整列（禁止 prefix）、升序和列序均固定。
-- runner: assert=scalar-true
SELECT COUNT(*) = 6
  AND SUM(index_type = 'BTREE' AND is_visible = 'YES'
    AND prefix_parts = 0 AND ascending_parts = parts) = 6
  AND SUM(CASE
    WHEN index_name = 'PRIMARY' AND parts = 1 AND non_unique = 0 AND cols = 'id' THEN 1
    WHEN index_name = 'uk_club_post_media_review_identity' AND parts = 3
      AND non_unique = 0 AND cols = 'post_id,post_version,media_hash' THEN 1
    WHEN index_name = 'uk_club_post_media_review_trace' AND parts = 1
      AND non_unique = 0 AND cols = 'trace_hash' THEN 1
    WHEN index_name = 'idx_club_post_media_review_due' AND parts = 4
      AND non_unique = 1 AND cols = 'state,next_attempt_at,lease_until,id' THEN 1
    WHEN index_name = 'idx_club_post_media_review_lease' AND parts = 3
      AND non_unique = 1 AND cols = 'state,lease_until,id' THEN 1
    WHEN index_name = 'idx_club_post_media_review_backlog' AND parts = 3
      AND non_unique = 1 AND cols = 'state,create_time,id' THEN 1
    ELSE 0 END) = 6 AS assertion_ok
FROM (
  SELECT index_name, COUNT(*) parts, MIN(non_unique) non_unique,
         MIN(index_type) index_type, MIN(is_visible) is_visible,
         SUM(sub_part IS NOT NULL) prefix_parts,
         SUM(collation = 'A') ascending_parts,
         GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') cols
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'club_post_media_review_outbox'
  GROUP BY index_name
) exact_indexes;

-- CHECK 不只验名字：五条单义表达式逐字回读，且必须由 MySQL 标记为 ENFORCED。
-- runner: assert=scalar-true
SELECT COUNT(*) = 5
  AND SUM(CASE
    WHEN cc.constraint_name = 'chk_club_post_media_review_state'
      AND REGEXP_REPLACE(LOWER(cc.check_clause),
          '[[:space:]`\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
          <=> 'statein0,1,2,3,4,5' THEN 1
    WHEN cc.constraint_name = 'chk_club_post_media_review_attempt'
      AND REGEXP_REPLACE(LOWER(cc.check_clause),
          '[[:space:]`\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
          <=> 'post_version>=0andattempt_count>=0andmax_attemptsbetween1and20' THEN 1
    WHEN cc.constraint_name = 'chk_club_post_media_review_owner'
      AND REGEXP_REPLACE(LOWER(cc.check_clause),
          '[[:space:]`\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
          <=> 'ifstate=1,owner_tokenisnotnull,owner_tokenisnull=1' THEN 1
    WHEN cc.constraint_name = 'chk_club_post_media_review_lease'
      AND REGEXP_REPLACE(LOWER(cc.check_clause),
          '[[:space:]`\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
          <=> 'ifstate=1,lease_untilisnotnull,lease_untilisnull=1' THEN 1
    WHEN cc.constraint_name = 'chk_club_post_media_review_trace'
      AND REGEXP_REPLACE(LOWER(cc.check_clause),
          '[[:space:]`\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
          <=> 'ifstate=2,trace_hashisnotnull,trace_hashisnull=1' THEN 1
    ELSE 0 END) = 5 AS assertion_ok
FROM information_schema.check_constraints cc
JOIN information_schema.table_constraints tc
  ON tc.constraint_schema = cc.constraint_schema
 AND tc.constraint_name = cc.constraint_name
WHERE cc.constraint_schema = DATABASE()
  AND tc.table_name = 'club_post_media_review_outbox'
  AND tc.constraint_type = 'CHECK' AND tc.enforced = 'YES';
