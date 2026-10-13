-- 城瘾专业社区广场：帖文聚合、互动、分发、治理、通知、搜索与擦除。
-- 阶段：pre-deploy schema + policy seed + legacy backfill。
-- 前置：migration_community_trust_20260828.sql、migration_compliance_consent_deregister_20260711.sql。
-- 执行顺序：先落本文件并完成全部 readback，再合并部署；维护任务必须在新 jar 上线后另行注册。

SET @drop_report_check := IF(
  EXISTS(SELECT 1 FROM information_schema.table_constraints
         WHERE constraint_schema=DATABASE() AND table_name='community_report_case'
           AND constraint_name='chk_community_report_target'),
  'ALTER TABLE community_report_case DROP CHECK chk_community_report_target',
  'SELECT 1'
);
PREPARE community_report_check_stmt FROM @drop_report_check;
EXECUTE community_report_check_stmt;
DEALLOCATE PREPARE community_report_check_stmt;

SET @add_report_policy_version := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='community_report_case'
           AND column_name='policy_version'),
  'SELECT 1',
  'ALTER TABLE community_report_case ADD COLUMN policy_version VARCHAR(64) NULL AFTER reason_code'
);
PREPARE community_report_policy_stmt FROM @add_report_policy_version;
EXECUTE community_report_policy_stmt;
DEALLOCATE PREPARE community_report_policy_stmt;

SET @add_report_reason_label_snapshot := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='community_report_case'
           AND column_name='reason_label_snapshot'),
  'SELECT 1',
  'ALTER TABLE community_report_case ADD COLUMN reason_label_snapshot VARCHAR(100) NULL AFTER policy_version'
);
PREPARE community_report_reason_label_stmt FROM @add_report_reason_label_snapshot;
EXECUTE community_report_reason_label_stmt;
DEALLOCATE PREPARE community_report_reason_label_stmt;

SET @add_report_priority_snapshot := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='community_report_case'
           AND column_name='priority_snapshot'),
  'SELECT 1',
  'ALTER TABLE community_report_case ADD COLUMN priority_snapshot VARCHAR(16) NULL AFTER reason_label_snapshot'
);
PREPARE community_report_priority_stmt FROM @add_report_priority_snapshot;
EXECUTE community_report_priority_stmt;
DEALLOCATE PREPARE community_report_priority_stmt;

SET @add_report_first_response_snapshot := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='community_report_case'
           AND column_name='first_response_minutes_snapshot'),
  'SELECT 1',
  'ALTER TABLE community_report_case ADD COLUMN first_response_minutes_snapshot INT NULL AFTER priority_snapshot'
);
PREPARE community_report_first_response_stmt FROM @add_report_first_response_snapshot;
EXECUTE community_report_first_response_stmt;
DEALLOCATE PREPARE community_report_first_response_stmt;

SET @drop_report_priority_check := IF(
  EXISTS(SELECT 1 FROM information_schema.table_constraints
         WHERE constraint_schema=DATABASE() AND table_name='community_report_case'
           AND constraint_name='chk_community_report_priority'),
  'ALTER TABLE community_report_case DROP CHECK chk_community_report_priority',
  'SELECT 1'
);
PREPARE community_drop_report_priority_check_stmt FROM @drop_report_priority_check;
EXECUTE community_drop_report_priority_check_stmt;
DEALLOCATE PREPARE community_drop_report_priority_check_stmt;

ALTER TABLE community_report_case
  ADD CONSTRAINT chk_community_report_priority
  CHECK (priority_snapshot IN ('URGENT','STANDARD'));

SET @drop_report_first_response_check := IF(
  EXISTS(SELECT 1 FROM information_schema.table_constraints
         WHERE constraint_schema=DATABASE() AND table_name='community_report_case'
           AND constraint_name='chk_community_report_first_response'),
  'ALTER TABLE community_report_case DROP CHECK chk_community_report_first_response',
  'SELECT 1'
);
PREPARE community_drop_report_first_response_check_stmt FROM @drop_report_first_response_check;
EXECUTE community_drop_report_first_response_check_stmt;
DEALLOCATE PREPARE community_drop_report_first_response_check_stmt;

ALTER TABLE community_report_case
  ADD CONSTRAINT chk_community_report_first_response
  CHECK (first_response_minutes_snapshot BETWEEN 1 AND 10080);

-- 下面 9 个索引守卫里 `non_unique` 必须出现在 SELECT 列表：information_schema 视图上
-- HAVING 只认投影里的列，写成 `GROUP BY index_name, non_unique HAVING non_unique=1`
-- 而 SELECT 只出 index_name 会直接 1054（2026-09-19 生产实跑坐实，本文件此前从未落成功过）。
-- 第 141 句的 CHECK 指纹要再多剥一层反斜杠：MySQL 8 的 information_schema.check_clause
-- 把字符串字面量存成 `_utf8mb4\'URGENT\'`，只去反引号/`_utf8mb4`/括号时哈希永远对不上
-- （2026-09-19 生产实跑：9 个联言项里只有这一项假，其余全 PASS）。
SET @drop_wrong_report_first_response_index := IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema=DATABASE() AND table_name='community_report_timeline'
           AND index_name='idx_community_report_first_response')
  AND NOT EXISTS(
    SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='community_report_timeline'
      AND index_name='idx_community_report_first_response'
    GROUP BY index_name, non_unique
    HAVING non_unique=1
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
           = 'actor_type,event_type,report_id,create_time'
  ),
  'ALTER TABLE community_report_timeline DROP INDEX idx_community_report_first_response',
  'SELECT 1'
);
PREPARE community_drop_report_first_response_index_stmt FROM @drop_wrong_report_first_response_index;
EXECUTE community_drop_report_first_response_index_stmt;
DEALLOCATE PREPARE community_drop_report_first_response_index_stmt;

SET @add_report_first_response_index := IF(
  EXISTS(
    SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='community_report_timeline'
      AND index_name='idx_community_report_first_response'
    GROUP BY index_name, non_unique
    HAVING non_unique=1
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
           = 'actor_type,event_type,report_id,create_time'
  ),
  'SELECT 1',
  'ALTER TABLE community_report_timeline ADD INDEX idx_community_report_first_response (actor_type, event_type, report_id, create_time)'
);
PREPARE community_report_first_response_index_stmt FROM @add_report_first_response_index;
EXECUTE community_report_first_response_index_stmt;
DEALLOCATE PREPARE community_report_first_response_index_stmt;

SET @add_biz_event_actor_scope := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='biz_event_log'
           AND column_name='actor_scope'),
  'SELECT 1',
  'ALTER TABLE biz_event_log ADD COLUMN actor_scope VARCHAR(96) NOT NULL DEFAULT ''ANONYMOUS'' AFTER idempotency_key'
);
PREPARE community_biz_event_actor_scope_stmt FROM @add_biz_event_actor_scope;
EXECUTE community_biz_event_actor_scope_stmt;
DEALLOCATE PREPARE community_biz_event_actor_scope_stmt;

-- 旧 jar 在新列已存在时仍会写默认 ANONYMOUS。若同一个登录用户随后由新 jar
-- 以 MEMBER:id 重试，先按 member_id 的权威作用域消重，否则下面 UPDATE 会先撞唯一键。
DELETE duplicate_event
FROM biz_event_log duplicate_event
JOIN biz_event_log canonical_event
  ON canonical_event.id < duplicate_event.id
 AND canonical_event.event_name=duplicate_event.event_name
 AND (CASE WHEN canonical_event.member_id IS NULL
           THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', canonical_event.member_id) END)
     = (CASE WHEN duplicate_event.member_id IS NULL
             THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', duplicate_event.member_id) END)
 AND canonical_event.idempotency_key=duplicate_event.idempotency_key
WHERE duplicate_event.idempotency_key IS NOT NULL;

UPDATE biz_event_log
SET actor_scope=CASE WHEN member_id IS NULL THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', member_id) END
WHERE actor_scope IS NULL OR actor_scope='' OR actor_scope='ANONYMOUS';

-- nullable member_id 不能承担匿名幂等。先收敛历史重复，再把唯一键切到非空 actor_scope。
DELETE duplicate_event
FROM biz_event_log duplicate_event
JOIN biz_event_log canonical_event
  ON canonical_event.id < duplicate_event.id
 AND canonical_event.event_name=duplicate_event.event_name
 AND canonical_event.actor_scope=duplicate_event.actor_scope
 AND canonical_event.idempotency_key=duplicate_event.idempotency_key
WHERE duplicate_event.idempotency_key IS NOT NULL;

SET @drop_wrong_biz_event_idem := IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema=DATABASE() AND table_name='biz_event_log'
           AND index_name='uk_biz_event_idem')
  AND NOT EXISTS(
    SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='biz_event_log'
      AND index_name='uk_biz_event_idem'
    GROUP BY index_name, non_unique
    HAVING non_unique=0
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
           = 'event_name,actor_scope,idempotency_key'
  ),
  'ALTER TABLE biz_event_log DROP INDEX uk_biz_event_idem',
  'SELECT 1'
);
PREPARE community_drop_biz_event_idem_stmt FROM @drop_wrong_biz_event_idem;
EXECUTE community_drop_biz_event_idem_stmt;
DEALLOCATE PREPARE community_drop_biz_event_idem_stmt;

SET @add_biz_event_idem := IF(
  EXISTS(
    SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='biz_event_log'
      AND index_name='uk_biz_event_idem'
    GROUP BY index_name, non_unique
    HAVING non_unique=0
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
           = 'event_name,actor_scope,idempotency_key'
  ),
  'SELECT 1',
  'ALTER TABLE biz_event_log ADD UNIQUE INDEX uk_biz_event_idem (event_name, actor_scope, idempotency_key)'
);
PREPARE community_biz_event_idem_stmt FROM @add_biz_event_idem;
EXECUTE community_biz_event_idem_stmt;
DEALLOCATE PREPARE community_biz_event_idem_stmt;

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='biz_event_log'
     AND column_name='actor_scope' AND is_nullable='NO'
     AND data_type='varchar' AND character_maximum_length=96) = 1
  AND
  (SELECT COUNT(*) FROM (
     SELECT index_name, non_unique FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='biz_event_log'
       AND index_name='uk_biz_event_idem'
     GROUP BY index_name, non_unique
     HAVING non_unique=0
        AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
            = 'event_name,actor_scope,idempotency_key'
   ) actor_idem_index) = 1 AS assertion_ok;

SET @drop_wrong_biz_event_occurred_index := IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema=DATABASE() AND table_name='biz_event_log'
           AND index_name='idx_biz_event_occurred_at')
  AND NOT EXISTS(
    SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='biz_event_log'
      AND index_name='idx_biz_event_occurred_at'
    GROUP BY index_name, non_unique
    HAVING non_unique=1
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')='occurred_at'
  ),
  'ALTER TABLE biz_event_log DROP INDEX idx_biz_event_occurred_at',
  'SELECT 1'
);
PREPARE community_drop_biz_event_occurred_index_stmt FROM @drop_wrong_biz_event_occurred_index;
EXECUTE community_drop_biz_event_occurred_index_stmt;
DEALLOCATE PREPARE community_drop_biz_event_occurred_index_stmt;

SET @add_biz_event_occurred_index := IF(
  EXISTS(
    SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='biz_event_log'
      AND index_name='idx_biz_event_occurred_at'
    GROUP BY index_name, non_unique
    HAVING non_unique=1
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')='occurred_at'
  ),
  'SELECT 1',
  'ALTER TABLE biz_event_log ADD INDEX idx_biz_event_occurred_at (occurred_at)'
);
PREPARE community_biz_event_occurred_index_stmt FROM @add_biz_event_occurred_index;
EXECUTE community_biz_event_occurred_index_stmt;
DEALLOCATE PREPARE community_biz_event_occurred_index_stmt;

ALTER TABLE community_report_case
  ADD CONSTRAINT chk_community_report_target CHECK (
    target_type IN ('MEMBER','MERCHANT','CLUB','ACTIVITY','CONTENT','COMMENT','SAFETY_RATING')
  );

CREATE TABLE IF NOT EXISTS community_guideline_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  version_code VARCHAR(32) NOT NULL,
  title VARCHAR(100) NOT NULL,
  summary VARCHAR(500) NOT NULL,
  content_json JSON NOT NULL,
  applies_to VARCHAR(32) NOT NULL DEFAULT 'PLAZA',
  status VARCHAR(16) NOT NULL,
  effective_at DATETIME NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_guideline_version (version_code),
  KEY idx_community_guideline_active (applies_to, status, effective_at, id),
  CONSTRAINT chk_community_guideline_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT chk_community_guideline_json CHECK (JSON_VALID(content_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广场社区规范版本';

CREATE TABLE IF NOT EXISTS community_guideline_ack (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  guideline_version_id BIGINT NOT NULL,
  scene VARCHAR(32) NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  acknowledged_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_guideline_ack (member_id, guideline_version_id, scene),
  UNIQUE KEY uk_community_guideline_ack_request (member_id, request_id),
  KEY idx_community_guideline_member (member_id, acknowledged_at),
  CONSTRAINT fk_community_guideline_ack_version FOREIGN KEY (guideline_version_id)
    REFERENCES community_guideline_version(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户广场规范确认回执';

CREATE TABLE IF NOT EXISTS community_post (
  id BIGINT NOT NULL AUTO_INCREMENT,
  author_id BIGINT NOT NULL,
  community_id BIGINT NULL,
  client_request_id VARCHAR(96) NOT NULL,
  post_type VARCHAR(32) NOT NULL,
  title VARCHAR(120) NULL,
  body VARCHAR(5000) NOT NULL,
  audience VARCHAR(16) NOT NULL,
  comment_policy VARCHAR(16) NOT NULL,
  reply_approval_enabled TINYINT(1) NOT NULL DEFAULT 0,
  slow_mode_seconds INT NOT NULL DEFAULT 0,
  lifecycle VARCHAR(16) NOT NULL,
  location_precision VARCHAR(16) NOT NULL DEFAULT 'NONE',
  city_code VARCHAR(32) NULL,
  poi_name VARCHAR(120) NULL,
  approximate_geohash VARCHAR(16) NULL COMMENT '最多保留模糊网格，禁止精确坐标',
  recommendation_eligible TINYINT(1) NOT NULL DEFAULT 1,
  search_eligible TINYINT(1) NOT NULL DEFAULT 1,
  interaction_enabled TINYINT(1) NOT NULL DEFAULT 1,
  disclosure_type VARCHAR(24) NOT NULL DEFAULT 'NONE',
  safety_labels_json JSON NOT NULL,
  guideline_version_id BIGINT NOT NULL,
  like_count INT UNSIGNED NOT NULL DEFAULT 0,
  comment_count INT UNSIGNED NOT NULL DEFAULT 0,
  bookmark_count INT UNSIGNED NOT NULL DEFAULT 0,
  share_count INT UNSIGNED NOT NULL DEFAULT 0,
  published_at DATETIME NULL,
  edited_at DATETIME NULL,
  deleted_at DATETIME NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_request (author_id, client_request_id),
  KEY idx_community_post_public (lifecycle, audience, published_at, id),
  KEY idx_community_post_author (author_id, lifecycle, id),
  KEY idx_community_post_city (city_code, lifecycle, published_at, id),
  KEY idx_community_post_community (community_id, lifecycle, published_at, id),
  CONSTRAINT chk_community_post_type CHECK (post_type IN ('MOMENT','ACTIVITY_RECAP','ROUTE_DISCOVERY','CITY_TIP','CLUB_ANNOUNCEMENT')),
  CONSTRAINT chk_community_post_audience CHECK (audience IN ('PUBLIC','FOLLOWERS','COMMUNITY','PRIVATE')),
  CONSTRAINT chk_community_post_comment CHECK (comment_policy IN ('EVERYONE','FOLLOWERS','MEMBERS','MENTIONED','OFF')),
  CONSTRAINT chk_community_post_lifecycle CHECK (lifecycle IN ('DRAFT','CHECKING','PUBLISHED','LIMITED','HIDDEN','REMOVED','DELETED')),
  CONSTRAINT chk_community_post_location CHECK (location_precision IN ('NONE','CITY','APPROXIMATE','EXACT')),
  CONSTRAINT chk_community_post_location_payload CHECK (
    (location_precision = 'NONE' AND city_code IS NULL AND poi_name IS NULL AND approximate_geohash IS NULL)
    OR (location_precision = 'CITY' AND CHAR_LENGTH(TRIM(city_code)) BETWEEN 1 AND 32
        AND approximate_geohash IS NULL)
    OR (location_precision = 'APPROXIMATE' AND CHAR_LENGTH(TRIM(city_code)) BETWEEN 1 AND 32
        AND approximate_geohash IS NOT NULL
        AND approximate_geohash REGEXP '^[0123456789bcdefghjkmnpqrstuvwxyz]{4,8}$')
    OR (location_precision = 'EXACT' AND audience = 'PRIVATE' AND approximate_geohash IS NOT NULL
        AND approximate_geohash REGEXP '^[0123456789bcdefghjkmnpqrstuvwxyz]{9,12}$')
  ),
  CONSTRAINT chk_community_post_disclosure CHECK (disclosure_type IN ('NONE','SPONSORED','GIFTED','MERCHANT_OWNER','MERCHANT_EMPLOYEE')),
  CONSTRAINT chk_community_post_safety_json CHECK (JSON_VALID(safety_labels_json)),
  CONSTRAINT chk_community_post_slow_mode CHECK (slow_mode_seconds BETWEEN 0 AND 86400),
  CONSTRAINT fk_community_post_guideline FOREIGN KEY (guideline_version_id)
    REFERENCES community_guideline_version(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广场帖文聚合根';

CREATE TABLE IF NOT EXISTS community_post_media (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_member_id BIGINT NOT NULL,
  upload_request_id VARCHAR(96) NOT NULL,
  upload_receipt_hash CHAR(64) NULL,
  post_id BIGINT NULL,
  media_type VARCHAR(20) NOT NULL,
  object_key VARCHAR(500) NOT NULL,
  derived_object_key VARCHAR(500) NULL,
  mime_type VARCHAR(100) NOT NULL,
  byte_size BIGINT UNSIGNED NOT NULL,
  width INT UNSIGNED NULL,
  height INT UNSIGNED NULL,
  duration_ms INT UNSIGNED NULL,
  sort_order INT UNSIGNED NOT NULL DEFAULT 0,
  alt_text VARCHAR(500) NULL,
  content_hash CHAR(64) NOT NULL,
  upload_state VARCHAR(16) NOT NULL,
  review_state VARCHAR(16) NOT NULL,
  retention_state VARCHAR(16) NOT NULL,
  committed_at DATETIME NULL,
  delete_after DATETIME NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_media_request (owner_member_id, upload_request_id),
  UNIQUE KEY uk_community_post_media_receipt (upload_receipt_hash),
  KEY idx_community_post_media_hash (owner_member_id, content_hash),
  KEY idx_community_post_media_post (post_id, sort_order, id),
  KEY idx_community_post_media_cleanup (retention_state, delete_after, id),
  CONSTRAINT chk_community_post_media_type CHECK (media_type IN ('IMAGE','VIDEO','MAP_SNAPSHOT','FILE')),
  CONSTRAINT chk_community_post_media_upload CHECK (upload_state IN ('INITIATED','UPLOADED','COMMITTED','ATTACHED','REJECTED')),
  CONSTRAINT chk_community_post_media_review CHECK (review_state IN ('PENDING','PASS','HOLD','REJECTED')),
  CONSTRAINT chk_community_post_media_retention CHECK (retention_state IN ('ACTIVE','ORPHANED','LEGAL_HOLD','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖文媒体资产与生命周期';

CREATE TABLE IF NOT EXISTS community_post_reference (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  reference_type VARCHAR(16) NOT NULL,
  reference_id BIGINT NOT NULL,
  snapshot_json JSON NOT NULL,
  privacy_snapshot VARCHAR(32) NOT NULL,
  invalidated_at DATETIME NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_reference (post_id, reference_type, reference_id),
  KEY idx_community_post_reference_target (reference_type, reference_id, post_id),
  CONSTRAINT chk_community_post_reference_type CHECK (reference_type IN ('ACTIVITY','TOPIC','ROUTE','CLUB','POI')),
  CONSTRAINT chk_community_post_reference_json CHECK (JSON_VALID(snapshot_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖文一等业务关联快照';

CREATE TABLE IF NOT EXISTS community_post_revision (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  editor_member_id BIGINT NOT NULL,
  snapshot_version INT UNSIGNED NOT NULL,
  immutable_snapshot_json JSON NOT NULL,
  edit_reason VARCHAR(500) NULL,
  review_state VARCHAR(16) NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_revision (post_id, snapshot_version),
  KEY idx_community_post_revision_post (post_id, id),
  CONSTRAINT chk_community_post_revision_json CHECK (JSON_VALID(immutable_snapshot_json)),
  CONSTRAINT chk_community_post_revision_review CHECK (review_state IN ('NOT_REQUIRED','PENDING','PASS','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖文不可变编辑历史';

CREATE TABLE IF NOT EXISTS community_post_member_action (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  action_type VARCHAR(32) NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  source VARCHAR(24) NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_member_action (post_id, member_id, action_type),
  UNIQUE KEY uk_community_post_action_request (member_id, request_id),
  KEY idx_community_post_action_member (member_id, action_type, id),
  CONSTRAINT chk_community_post_action_type CHECK (action_type IN ('LIKE','BOOKMARK','NOT_INTERESTED','SEE_LESS_AUTHOR','SEE_LESS_TOPIC'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖文公开正向互动与私有分发反馈';

CREATE TABLE IF NOT EXISTS community_post_command_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  command_type VARCHAR(80) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  result_json JSON NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_command_request (member_id, request_id),
  KEY idx_community_post_command_aggregate (aggregate_id, id),
  CONSTRAINT chk_community_post_command_result CHECK (JSON_VALID(result_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布删除分享等状态命令的幂等回执';

CREATE TABLE IF NOT EXISTS community_post_comment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  author_id BIGINT NOT NULL,
  root_id BIGINT NULL,
  parent_id BIGINT NULL,
  replied_to_member_id BIGINT NULL,
  client_request_id VARCHAR(96) NOT NULL,
  body VARCHAR(2000) NOT NULL,
  lifecycle VARCHAR(16) NOT NULL,
  author_approval_state VARCHAR(16) NOT NULL DEFAULT 'VISIBLE',
  like_count INT UNSIGNED NOT NULL DEFAULT 0,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  published_at DATETIME NULL,
  deleted_at DATETIME NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_comment_request (author_id, client_request_id),
  KEY idx_community_post_comment_root (post_id, root_id, id),
  KEY idx_community_post_comment_parent (parent_id, id),
  CONSTRAINT chk_community_post_comment_lifecycle CHECK (lifecycle IN ('CHECKING','PUBLISHED','HIDDEN','REMOVED','DELETED')),
  CONSTRAINT chk_community_post_comment_approval CHECK (author_approval_state IN ('PENDING','VISIBLE','HIDDEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='稳定根节点和父节点的帖文对话树';

CREATE TABLE IF NOT EXISTS community_post_comment_action (
  id BIGINT NOT NULL AUTO_INCREMENT,
  comment_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  action_type VARCHAR(16) NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_comment_action (comment_id, member_id, action_type),
  UNIQUE KEY uk_community_comment_action_request (member_id, request_id),
  CONSTRAINT chk_community_comment_action_type CHECK (action_type IN ('LIKE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论互动关系';

CREATE TABLE IF NOT EXISTS community_post_topic (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  topic_code VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_topic (post_id, topic_code),
  KEY idx_community_post_topic_feed (topic_code, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖文发现话题';

CREATE TABLE IF NOT EXISTS community_post_mention (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  mentioned_member_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_mention (post_id, mentioned_member_id),
  KEY idx_community_post_mention_member (mentioned_member_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖文显式提及关系';

CREATE TABLE IF NOT EXISTS community_member_mute (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  muted_member_id BIGINT NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_member_mute (member_id, muted_member_id),
  UNIQUE KEY uk_community_member_mute_request (member_id, request_id),
  CONSTRAINT chk_community_member_mute_self CHECK (member_id <> muted_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私有静音关系';

CREATE TABLE IF NOT EXISTS community_featured_entry (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  collection_code VARCHAR(64) NOT NULL,
  city_code VARCHAR(32) NULL,
  reason VARCHAR(500) NOT NULL,
  operator_user_id BIGINT NOT NULL,
  starts_at DATETIME NOT NULL,
  ends_at DATETIME NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_featured_entry (post_id, collection_code),
  KEY idx_community_featured_active (collection_code, starts_at, ends_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运营与版主精选集合';

CREATE TABLE IF NOT EXISTS community_post_enforcement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NULL,
  target_member_id BIGINT NULL,
  enforcement_type VARCHAR(32) NOT NULL,
  rule_code VARCHAR(64) NOT NULL,
  guideline_version_id BIGINT NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  previous_lifecycle VARCHAR(16) NULL,
  evidence_snapshot_json JSON NOT NULL,
  operator_user_id BIGINT NOT NULL,
  starts_at DATETIME NOT NULL,
  ends_at DATETIME NULL,
  status VARCHAR(16) NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_enforcement_request (operator_user_id, request_id),
  KEY idx_community_enforcement_post (post_id, status, id),
  KEY idx_community_enforcement_member (target_member_id, status, ends_at, id),
  CONSTRAINT chk_community_enforcement_type CHECK (enforcement_type IN ('LABEL','DOWNRANK','SEARCH_EXCLUDE','INTERACTION_LIMIT','HIDE_PENDING_REVIEW','REMOVE','WARNING','POST_RATE_LIMIT','COMMENT_TIMEOUT','READ_ONLY','TEMP_SUSPEND','PERMANENT_BAN')),
  CONSTRAINT chk_community_enforcement_status CHECK (status IN ('ACTIVE','EXPIRED','REVERSED')),
  CONSTRAINT chk_community_enforcement_previous_lifecycle CHECK (previous_lifecycle IS NULL OR previous_lifecycle IN ('PUBLISHED','LIMITED','HIDDEN','REMOVED')),
  CONSTRAINT chk_community_enforcement_evidence CHECK (JSON_VALID(evidence_snapshot_json)),
  CONSTRAINT chk_community_enforcement_target CHECK (post_id IS NOT NULL OR target_member_id IS NOT NULL),
  CONSTRAINT fk_community_enforcement_guideline FOREIGN KEY (guideline_version_id)
    REFERENCES community_guideline_version(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容与账号梯度处置';

CREATE TABLE IF NOT EXISTS community_post_appeal (
  id BIGINT NOT NULL AUTO_INCREMENT,
  enforcement_id BIGINT NOT NULL,
  appellant_member_id BIGINT NOT NULL,
  reason VARCHAR(2000) NOT NULL,
  evidence_asset_ids_json JSON NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  status VARCHAR(16) NOT NULL,
  reviewer_user_id BIGINT NULL,
  reviewed_at DATETIME NULL,
  decision_reason VARCHAR(1000) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_appeal_request (appellant_member_id, request_id),
  UNIQUE KEY uk_community_post_active_appeal (enforcement_id, status),
  KEY idx_community_post_appeal_queue (status, create_time, id),
  CONSTRAINT chk_community_post_appeal_status CHECK (status IN ('SUBMITTED','REVIEWING','UPHELD','REVERSED')),
  CONSTRAINT chk_community_post_appeal_evidence CHECK (JSON_VALID(evidence_asset_ids_json)),
  CONSTRAINT fk_community_appeal_enforcement FOREIGN KEY (enforcement_id)
    REFERENCES community_post_enforcement(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='处置的独立复核申诉';

CREATE TABLE IF NOT EXISTS community_post_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id CHAR(36) NOT NULL,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  payload_json JSON NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL,
  processed_at DATETIME NULL,
  last_error_code VARCHAR(64) NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_post_outbox_event (event_id),
  KEY idx_community_post_outbox_due (state, next_attempt_at, id),
  CONSTRAINT chk_community_post_outbox_state CHECK (state IN ('PENDING','PROCESSING','DONE','RETRY','DEAD')),
  CONSTRAINT chk_community_post_outbox_aggregate CHECK (aggregate_type IN ('COMMUNITY_POST','COMMUNITY_MEMBER','COMMUNITY_NOTIFICATION')),
  CONSTRAINT chk_community_post_outbox_json CHECK (JSON_VALID(payload_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖文搜索、通知、Feed 投影统一 outbox';

CREATE TABLE IF NOT EXISTS community_notification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  recipient_member_id BIGINT NOT NULL,
  event_id CHAR(36) NOT NULL,
  post_id BIGINT NULL,
  comment_id BIGINT NULL,
  actor_member_id BIGINT NULL,
  notification_type VARCHAR(32) NOT NULL,
  aggregate_key VARCHAR(128) NOT NULL,
  actor_count INT UNSIGNED NOT NULL DEFAULT 1,
  payload_json JSON NOT NULL,
  read_at DATETIME NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_notification_event (recipient_member_id, event_id),
  KEY idx_community_notification_inbox (recipient_member_id, read_at, id),
  UNIQUE KEY uk_community_notification_aggregate (recipient_member_id, notification_type, aggregate_key),
  KEY idx_community_notification_post (post_id, id),
  KEY idx_community_notification_comment (comment_id, id),
  KEY idx_community_notification_actor (actor_member_id, id),
  CONSTRAINT chk_community_notification_type CHECK (notification_type IN ('INTERACTION','COMMENT_MENTION','SOCIAL','SYSTEM_GOVERNANCE')),
  CONSTRAINT chk_community_notification_json CHECK (JSON_VALID(payload_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='去重可聚合的社区通知';

CREATE TABLE IF NOT EXISTS community_notification_preference (
  member_id BIGINT NOT NULL,
  interaction_enabled TINYINT(1) NOT NULL DEFAULT 1,
  mention_enabled TINYINT(1) NOT NULL DEFAULT 1,
  social_enabled TINYINT(1) NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区通知偏好，治理通知始终送达';

CREATE TABLE IF NOT EXISTS community_notification_actor (
  recipient_member_id BIGINT NOT NULL,
  notification_type VARCHAR(32) NOT NULL,
  aggregate_key VARCHAR(128) NOT NULL,
  actor_member_id BIGINT NOT NULL,
  last_event_id CHAR(36) NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (recipient_member_id, notification_type, aggregate_key, actor_member_id),
  KEY idx_community_notification_actor_visibility (recipient_member_id, actor_member_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚合通知的行为人明细，用于屏蔽/静音后逐人重算';

CREATE TABLE IF NOT EXISTS community_member_governance_state (
  member_id BIGINT NOT NULL,
  last_event_type VARCHAR(48) NOT NULL,
  last_event_payload_json JSON NOT NULL,
  last_event_at DATETIME NOT NULL,
  version INT UNSIGNED NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (member_id),
  KEY idx_community_member_governance_event (last_event_type, last_event_at),
  CONSTRAINT chk_community_member_governance_json CHECK (JSON_VALID(last_event_payload_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可重放的账号级社区治理投影';

CREATE TABLE IF NOT EXISTS community_search_document (
  id BIGINT NOT NULL AUTO_INCREMENT,
  document_type VARCHAR(16) NOT NULL,
  document_id BIGINT NOT NULL,
  owner_member_id BIGINT NULL,
  title VARCHAR(200) NULL,
  search_text TEXT NOT NULL,
  city_code VARCHAR(32) NULL,
  topic_codes_json JSON NOT NULL,
  visibility_state VARCHAR(16) NOT NULL,
  source_version INT UNSIGNED NOT NULL,
  indexed_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_search_document (document_type, document_id),
  KEY idx_community_search_visible (document_type, visibility_state, city_code, id),
  CONSTRAINT chk_community_search_type CHECK (document_type IN ('POST','MEMBER','TOPIC','COMMUNITY','POI')),
  CONSTRAINT chk_community_search_visibility CHECK (visibility_state IN ('VISIBLE','HIDDEN','DELETED')),
  CONSTRAINT chk_community_search_topics CHECK (JSON_VALID(topic_codes_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='类型化社区搜索投影';

CREATE TABLE IF NOT EXISTS community_erasure_job (
  id BIGINT NOT NULL AUTO_INCREMENT,
  subject_member_id BIGINT NULL,
  post_id BIGINT NULL,
  trigger_type VARCHAR(32) NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  legal_hold TINYINT(1) NOT NULL DEFAULT 0,
  checklist_json JSON NOT NULL,
  state VARCHAR(16) NOT NULL,
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL,
  completed_at DATETIME NULL,
  last_error_code VARCHAR(64) NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_community_erasure_request (request_id),
  KEY idx_community_erasure_due (state, next_attempt_at, id),
  CONSTRAINT chk_community_erasure_trigger CHECK (trigger_type IN ('POST_DELETE','ACCOUNT_DEREGISTER','LOCATION_WITHDRAW','COPYRIGHT_REMOVE')),
  CONSTRAINT chk_community_erasure_state CHECK (state IN ('PENDING','PROCESSING','DONE','RETRY','BLOCKED_LEGAL_HOLD')),
  CONSTRAINT chk_community_erasure_target CHECK (subject_member_id IS NOT NULL OR post_id IS NOT NULL),
  CONSTRAINT chk_community_erasure_json CHECK (JSON_VALID(checklist_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖文删除、注销、位置撤回和版权移除编排';

-- 旧形状恢复：IF NOT EXISTS 只能保证表存在，不会为早期表补新列/索引/约束。
SET @community_add_notification_comment := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='community_notification' AND column_name='comment_id'),
  'SELECT 1',
  'ALTER TABLE community_notification ADD COLUMN comment_id BIGINT NULL AFTER post_id'
);
PREPARE community_add_notification_comment_stmt FROM @community_add_notification_comment;
EXECUTE community_add_notification_comment_stmt;
DEALLOCATE PREPARE community_add_notification_comment_stmt;

SET @community_add_notification_comment_idx := IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema=DATABASE() AND table_name='community_notification'
           AND index_name='idx_community_notification_comment'),
  'SELECT 1',
  'ALTER TABLE community_notification ADD KEY idx_community_notification_comment (comment_id,id)'
);
PREPARE community_add_notification_comment_idx_stmt FROM @community_add_notification_comment_idx;
EXECUTE community_add_notification_comment_idx_stmt;
DEALLOCATE PREPARE community_add_notification_comment_idx_stmt;

SET @community_add_notification_aggregate_idx := IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema=DATABASE() AND table_name='community_notification'
           AND index_name='uk_community_notification_aggregate'),
  'SELECT 1',
  'ALTER TABLE community_notification ADD UNIQUE KEY uk_community_notification_aggregate (recipient_member_id,notification_type,aggregate_key)'
);
PREPARE community_add_notification_aggregate_idx_stmt FROM @community_add_notification_aggregate_idx;
EXECUTE community_add_notification_aggregate_idx_stmt;
DEALLOCATE PREPARE community_add_notification_aggregate_idx_stmt;

SET @community_drop_outbox_aggregate_check := IF(
  EXISTS(SELECT 1 FROM information_schema.table_constraints
         WHERE constraint_schema=DATABASE() AND table_name='community_post_outbox'
           AND constraint_name='chk_community_post_outbox_aggregate'),
  'ALTER TABLE community_post_outbox DROP CHECK chk_community_post_outbox_aggregate',
  'SELECT 1'
);
PREPARE community_drop_outbox_aggregate_check_stmt FROM @community_drop_outbox_aggregate_check;
EXECUTE community_drop_outbox_aggregate_check_stmt;
DEALLOCATE PREPARE community_drop_outbox_aggregate_check_stmt;
ALTER TABLE community_post_outbox
  ADD CONSTRAINT chk_community_post_outbox_aggregate
  CHECK (aggregate_type IN ('COMMUNITY_POST','COMMUNITY_MEMBER','COMMUNITY_NOTIFICATION'));

SET @community_drop_notification_type_check := IF(
  EXISTS(SELECT 1 FROM information_schema.table_constraints
         WHERE constraint_schema=DATABASE() AND table_name='community_notification'
           AND constraint_name='chk_community_notification_type'),
  'ALTER TABLE community_notification DROP CHECK chk_community_notification_type',
  'SELECT 1'
);
PREPARE community_drop_notification_type_check_stmt FROM @community_drop_notification_type_check;
EXECUTE community_drop_notification_type_check_stmt;
DEALLOCATE PREPARE community_drop_notification_type_check_stmt;
ALTER TABLE community_notification
  ADD CONSTRAINT chk_community_notification_type
  CHECK (notification_type IN ('INTERACTION','COMMENT_MENTION','SOCIAL','SYSTEM_GOVERNANCE'));

INSERT INTO community_notification_actor
  (recipient_member_id,notification_type,aggregate_key,actor_member_id,last_event_id,create_time,update_time)
SELECT recipient_member_id,notification_type,aggregate_key,actor_member_id,event_id,create_time,update_time
FROM community_notification
WHERE actor_member_id IS NOT NULL AND actor_member_id > 0 AND notification_type != 'SYSTEM_GOVERNANCE'
ON DUPLICATE KEY UPDATE last_event_id=VALUES(last_event_id),update_time=VALUES(update_time);

UPDATE community_notification n
SET n.actor_count=(
  SELECT COUNT(*) FROM community_notification_actor a
  WHERE a.recipient_member_id=n.recipient_member_id AND a.notification_type=n.notification_type
    AND a.aggregate_key=n.aggregate_key
)
WHERE n.notification_type!='SYSTEM_GOVERNANCE';

SET @community_notification_actor_fk_shape_ok := (
  EXISTS(SELECT 1 FROM information_schema.referential_constraints
         WHERE constraint_schema=DATABASE()
           AND table_name='community_notification_actor'
           AND constraint_name='fk_community_notification_actor_parent'
           AND referenced_table_name='community_notification'
           AND delete_rule='CASCADE')
  AND 3=(SELECT COUNT(*) FROM information_schema.key_column_usage
         WHERE constraint_schema=DATABASE() AND table_name='community_notification_actor'
           AND constraint_name='fk_community_notification_actor_parent'
           AND ((ordinal_position=1 AND column_name='recipient_member_id' AND referenced_column_name='recipient_member_id')
             OR (ordinal_position=2 AND column_name='notification_type' AND referenced_column_name='notification_type')
             OR (ordinal_position=3 AND column_name='aggregate_key' AND referenced_column_name='aggregate_key')))
);
SET @community_drop_notification_actor_fk := IF(
  EXISTS(SELECT 1 FROM information_schema.referential_constraints
         WHERE constraint_schema=DATABASE() AND table_name='community_notification_actor'
           AND constraint_name='fk_community_notification_actor_parent')
    AND NOT @community_notification_actor_fk_shape_ok,
  'ALTER TABLE community_notification_actor DROP FOREIGN KEY fk_community_notification_actor_parent',
  'SELECT 1'
);
PREPARE community_drop_notification_actor_fk_stmt FROM @community_drop_notification_actor_fk;
EXECUTE community_drop_notification_actor_fk_stmt;
DEALLOCATE PREPARE community_drop_notification_actor_fk_stmt;

SET @community_add_notification_actor_fk := IF(
  @community_notification_actor_fk_shape_ok,
  'SELECT 1',
  'ALTER TABLE community_notification_actor ADD CONSTRAINT fk_community_notification_actor_parent FOREIGN KEY (recipient_member_id,notification_type,aggregate_key) REFERENCES community_notification(recipient_member_id,notification_type,aggregate_key) ON DELETE CASCADE'
);
PREPARE community_add_notification_actor_fk_stmt FROM @community_add_notification_actor_fk;
EXECUTE community_add_notification_actor_fk_stmt;
DEALLOCATE PREPARE community_add_notification_actor_fk_stmt;

INSERT INTO community_guideline_version
  (version_code, title, summary, content_json, applies_to, status, effective_at, create_time, update_time)
SELECT 'CITY_EXPLORER_1', '城市探索者公约', '真实、在地、互助、安全探索',
       JSON_OBJECT('rules', JSON_ARRAY(
         JSON_OBJECT('code','AUTHENTICITY','title','真实体验：禁止伪造、搬运和刷量'),
         JSON_OBJECT('code','USEFULNESS','title','帮助下一位探索者：补充难度、花费、无障碍和安全提醒'),
         JSON_OBJECT('code','LOCAL_PRIVACY','title','尊重在地的人：不骚扰、不擅闯、不曝光私人地址或人脸'),
         JSON_OBJECT('code','SAFETY','title','安全先于挑战：危险地点必须标记，禁止鼓励高风险行为'),
         JSON_OBJECT('code','DISCLOSURE','title','利益透明：商单、赠品、主理人或商家身份必须披露'),
         JSON_OBJECT('code','CIVILITY','title','友善讨论：反对攻击、歧视、围攻和人肉'),
         JSON_OBJECT('code','COPYRIGHT','title','尊重版权：图片、路线和文字注明来源')
       )),
       'PLAZA', 'ACTIVE', NOW(), NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM community_guideline_version WHERE version_code = 'CITY_EXPLORER_1'
);

-- 旧广场一次性离线回填。新旧表不双写；保留原主键，既有 /square/{id} 分享链接继续有效。
SET @community_guideline_id := (
  SELECT id FROM community_guideline_version
  WHERE version_code = 'CITY_EXPLORER_1' LIMIT 1
);

INSERT IGNORE INTO community_post
  (id, author_id, community_id, client_request_id, post_type, title, body, audience, comment_policy,
   reply_approval_enabled, slow_mode_seconds, lifecycle, location_precision, city_code, poi_name,
   approximate_geohash, recommendation_eligible, search_eligible, interaction_enabled, disclosure_type,
   safety_labels_json, guideline_version_id, like_count, comment_count, bookmark_count, share_count,
   published_at, edited_at, deleted_at, version, create_time, update_time)
SELECT s.id, s.member_id, NULL, CONCAT('legacy-square-', s.id),
       CASE WHEN s.data_type = 1 THEN 'ACTIVITY_RECAP'
            WHEN s.data_type = 2 THEN 'MOMENT'
            WHEN s.data_type = 3 THEN 'ROUTE_DISCOVERY' ELSE 'MOMENT' END,
       NULL, LEFT(COALESCE(NULLIF(TRIM(s.contents), ''), '[历史帖文]'), 5000),
       'PUBLIC', 'EVERYONE', 0, 0,
       CASE WHEN s.del_flag = 2 THEN 'DELETED'
            WHEN s.status = 1 THEN 'PUBLISHED'
            WHEN s.status = 0 THEN 'CHECKING' ELSE 'REMOVED' END,
       'NONE', NULL, NULL, NULL,
       CASE WHEN s.status = 1 AND COALESCE(s.del_flag, 0) = 0 THEN 1 ELSE 0 END,
       CASE WHEN s.status = 1 AND COALESCE(s.del_flag, 0) = 0 THEN 1 ELSE 0 END,
       CASE WHEN s.status = 1 AND COALESCE(s.del_flag, 0) = 0 THEN 1 ELSE 0 END,
       'NONE', JSON_ARRAY('LEGACY_MIGRATED'), @community_guideline_id,
       0, 0, 0, 0,
       CASE WHEN s.status = 1 AND COALESCE(s.del_flag, 0) = 0 THEN COALESCE(s.create_time, NOW()) ELSE NULL END,
       s.update_time,
       CASE WHEN s.del_flag = 2 THEN COALESCE(s.update_time, s.create_time, NOW()) ELSE NULL END,
       0, COALESCE(s.create_time, NOW()), COALESCE(s.update_time, s.create_time, NOW())
FROM ums_creative_square s
WHERE s.member_id IS NOT NULL;

INSERT IGNORE INTO community_post_member_action
  (post_id, member_id, action_type, request_id, source, create_time, update_time)
SELECT l.data_id, l.member_id, 'LIKE', CONCAT('legacy-square-like-', l.id), 'LEGACY_MIGRATION',
       COALESCE(l.create_time, NOW()), COALESCE(l.update_time, l.create_time, NOW())
FROM ums_creative_square_like l
JOIN community_post p ON p.id = l.data_id
WHERE l.like_type = 1 AND l.member_id IS NOT NULL;

INSERT IGNORE INTO community_post_comment
  (id, post_id, author_id, root_id, parent_id, replied_to_member_id, client_request_id, body,
   lifecycle, author_approval_state, like_count, version, published_at, deleted_at, create_time, update_time)
WITH RECURSIVE legacy_comment_tree AS (
  SELECT c.id AS descendant_id, c.id AS root_id, c.owner_id
  FROM ums_comment c
  WHERE c.owner_type = 3 AND (c.reply_id IS NULL OR c.reply_id = 0)
  UNION ALL
  SELECT child.id, tree.root_id, child.owner_id
  FROM legacy_comment_tree tree
  JOIN ums_comment child
    ON child.reply_id = tree.descendant_id
   AND child.owner_type = 3
   AND child.owner_id = tree.owner_id
)
SELECT c.id, c.owner_id, c.member_id,
       CASE WHEN c.reply_id IS NULL OR c.reply_id = 0 THEN NULL ELSE tree.root_id END,
       CASE WHEN c.reply_id IS NULL OR c.reply_id = 0 THEN NULL ELSE c.reply_id END,
       parent.member_id, CONCAT('legacy-square-comment-', c.id),
       LEFT(COALESCE(NULLIF(TRIM(c.contents), ''), '[历史回复]'), 2000),
       CASE WHEN c.del_flag = 2 THEN 'DELETED' WHEN c.status = 1 THEN 'PUBLISHED' ELSE 'REMOVED' END,
       'VISIBLE', 0, 0,
       CASE WHEN c.status = 1 AND COALESCE(c.del_flag, 0) = 0 THEN COALESCE(c.create_time, NOW()) ELSE NULL END,
       CASE WHEN c.del_flag = 2 THEN COALESCE(c.update_time, c.create_time, NOW()) ELSE NULL END,
       COALESCE(c.create_time, NOW()), COALESCE(c.update_time, c.create_time, NOW())
FROM ums_comment c
JOIN community_post p ON p.id = c.owner_id
LEFT JOIN legacy_comment_tree tree ON tree.descendant_id = c.id AND tree.owner_id = c.owner_id
LEFT JOIN ums_comment parent ON parent.id = c.reply_id AND parent.owner_type = 3 AND parent.owner_id = c.owner_id
WHERE c.owner_type = 3 AND c.member_id IS NOT NULL;

INSERT IGNORE INTO community_post_comment_action
  (comment_id, member_id, action_type, request_id, create_time)
SELECT l.comment_id, l.member_id, 'LIKE', CONCAT('legacy-comment-like-', l.id), COALESCE(l.create_time, NOW())
FROM ums_comment_like l
JOIN community_post_comment c ON c.id = l.comment_id
WHERE l.member_id IS NOT NULL;

INSERT IGNORE INTO community_post_reference
  (post_id, reference_type, reference_id, snapshot_json, privacy_snapshot, create_time)
SELECT s.id,
       CASE WHEN s.data_type = 1 THEN 'ACTIVITY' WHEN s.data_type = 2 THEN 'TOPIC' ELSE 'ROUTE' END,
       s.data_id, JSON_OBJECT('legacy', TRUE), 'PUBLIC_SAFE', COALESCE(s.create_time, NOW())
FROM ums_creative_square s
JOIN community_post p ON p.id = s.id
WHERE s.data_id IS NOT NULL AND s.data_id > 0 AND s.data_type IN (1, 2, 3);

-- MySQL 8 JSON_TABLE 把旧分号图片列拆成一图一资产；历史字节数不可追溯，以 1 标记 unknown。
INSERT IGNORE INTO community_post_media
  (owner_member_id, upload_request_id, post_id, media_type, object_key, derived_object_key, mime_type,
   byte_size, sort_order, content_hash, upload_state, review_state, retention_state, committed_at, create_time, update_time)
SELECT s.member_id, CONCAT('legacy-square-media-', s.id, '-', media.ordinality), s.id, 'IMAGE', media.url, media.url,
       'image/jpeg', 1, media.ordinality - 1, SHA2(media.url, 256), 'ATTACHED',
       CASE WHEN s.status = 1 AND COALESCE(s.del_flag, 0) = 0 THEN 'PASS' ELSE 'PENDING' END,
       CASE WHEN s.del_flag = 2 THEN 'ORPHANED' ELSE 'ACTIVE' END,
       COALESCE(s.create_time, NOW()), COALESCE(s.create_time, NOW()), COALESCE(s.update_time, s.create_time, NOW())
FROM ums_creative_square s
CROSS JOIN JSON_TABLE(
  CONCAT('[', CHAR(34),
    REPLACE(REPLACE(REPLACE(TRIM(s.pics), CHAR(92), CONCAT(CHAR(92), CHAR(92))), CHAR(34), CONCAT(CHAR(92), CHAR(34))),
      ';', CONCAT(CHAR(34), ',', CHAR(34))),
    CHAR(34), ']'),
  '$[*]' COLUMNS(ordinality FOR ORDINALITY, url VARCHAR(500) PATH '$')
) media
WHERE s.member_id IS NOT NULL AND s.pics IS NOT NULL AND TRIM(s.pics) <> '' AND TRIM(media.url) <> '';

-- 旧附件列同样是分号串，按 FILE 资产迁移；不猜 MIME，不把附件伪装成图片。
INSERT IGNORE INTO community_post_media
  (owner_member_id, upload_request_id, post_id, media_type, object_key, derived_object_key, mime_type,
   byte_size, sort_order, content_hash, upload_state, review_state, retention_state, committed_at, create_time, update_time)
SELECT s.member_id, CONCAT('legacy-square-file-', s.id, '-', file_asset.ordinality), s.id, 'FILE',
       file_asset.url, file_asset.url, 'application/octet-stream', 1, 100 + file_asset.ordinality,
       SHA2(file_asset.url, 256), 'ATTACHED',
       CASE WHEN s.status = 1 AND COALESCE(s.del_flag, 0) = 0 THEN 'PASS' ELSE 'PENDING' END,
       CASE WHEN s.del_flag = 2 THEN 'ORPHANED' ELSE 'ACTIVE' END,
       COALESCE(s.create_time, NOW()), COALESCE(s.create_time, NOW()), COALESCE(s.update_time, s.create_time, NOW())
FROM ums_creative_square s
CROSS JOIN JSON_TABLE(
  CONCAT('[', CHAR(34),
    REPLACE(REPLACE(REPLACE(TRIM(s.file_url), CHAR(92), CONCAT(CHAR(92), CHAR(92))), CHAR(34), CONCAT(CHAR(92), CHAR(34))),
      ';', CONCAT(CHAR(34), ',', CHAR(34))),
    CHAR(34), ']'),
  '$[*]' COLUMNS(ordinality FOR ORDINALITY, url VARCHAR(500) PATH '$')
) file_asset
WHERE s.member_id IS NOT NULL AND s.file_url IS NOT NULL AND TRIM(s.file_url) <> ''
  AND TRIM(file_asset.url) <> '';

INSERT IGNORE INTO community_post_media
  (owner_member_id, upload_request_id, post_id, media_type, object_key, derived_object_key, mime_type,
   byte_size, sort_order, content_hash, upload_state, review_state, retention_state, committed_at, create_time, update_time)
SELECT s.member_id, CONCAT('legacy-square-route-', s.id), s.id, 'MAP_SNAPSHOT', s.route_preview_img, s.route_preview_img,
       'image/jpeg', 1, 999, SHA2(s.route_preview_img, 256), 'ATTACHED',
       CASE WHEN s.status = 1 AND COALESCE(s.del_flag, 0) = 0 THEN 'PASS' ELSE 'PENDING' END,
       CASE WHEN s.del_flag = 2 THEN 'ORPHANED' ELSE 'ACTIVE' END,
       COALESCE(s.create_time, NOW()), COALESCE(s.create_time, NOW()), COALESCE(s.update_time, s.create_time, NOW())
FROM ums_creative_square s
WHERE s.member_id IS NOT NULL AND s.route_preview_img IS NOT NULL AND TRIM(s.route_preview_img) <> '';

UPDATE community_post p SET
  like_count = (SELECT COUNT(*) FROM community_post_member_action a WHERE a.post_id = p.id AND a.action_type = 'LIKE'),
  comment_count = (SELECT COUNT(*) FROM community_post_comment c WHERE c.post_id = p.id
                   AND c.lifecycle = 'PUBLISHED' AND c.author_approval_state = 'VISIBLE')
WHERE p.client_request_id LIKE 'legacy-square-%';

INSERT INTO community_search_document
  (document_type, document_id, owner_member_id, title, search_text, city_code, topic_codes_json,
   visibility_state, source_version, indexed_at)
SELECT 'POST', p.id, p.author_id, p.title, CONCAT(COALESCE(p.title, ''), ' ', p.body), p.city_code, JSON_ARRAY(),
       CASE WHEN p.lifecycle = 'PUBLISHED' THEN 'VISIBLE'
            WHEN p.lifecycle = 'DELETED' THEN 'DELETED' ELSE 'HIDDEN' END,
       p.version, NOW()
FROM community_post p
WHERE p.client_request_id LIKE 'legacy-square-%'
ON DUPLICATE KEY UPDATE owner_member_id = VALUES(owner_member_id), title = VALUES(title),
  search_text = VALUES(search_text), city_code = VALUES(city_code), visibility_state = VALUES(visibility_state),
  source_version = VALUES(source_version), indexed_at = VALUES(indexed_at);

-- 旧有效帖与新版回填数必须一致，防止切源后历史内容静默消失。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM ums_creative_square WHERE member_id IS NOT NULL)
  =
  (SELECT COUNT(*) FROM community_post WHERE client_request_id LIKE 'legacy-square-%') AS assertion_ok;

-- 每类历史子数据都单独对账；INSERT IGNORE 发生冲突、JSON 拆分漏项或评论树断裂时必须红。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*)
   FROM ums_creative_square_like l
   JOIN ums_creative_square s ON s.id = l.data_id AND s.member_id IS NOT NULL
   WHERE l.like_type = 1 AND l.member_id IS NOT NULL)
  =
  (SELECT COUNT(*) FROM community_post_member_action
   WHERE action_type = 'LIKE' AND request_id LIKE 'legacy-square-like-%') AS assertion_ok;

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*)
   FROM ums_comment c
   JOIN ums_creative_square s ON s.id = c.owner_id AND s.member_id IS NOT NULL
   WHERE c.owner_type = 3 AND c.member_id IS NOT NULL)
  =
  (SELECT COUNT(*) FROM community_post_comment
   WHERE client_request_id LIKE 'legacy-square-comment-%') AS assertion_ok;

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*)
   FROM ums_comment_like l
   JOIN ums_comment c ON c.id = l.comment_id AND c.owner_type = 3 AND c.member_id IS NOT NULL
   JOIN ums_creative_square s ON s.id = c.owner_id AND s.member_id IS NOT NULL
   WHERE l.member_id IS NOT NULL)
  =
  (SELECT COUNT(*) FROM community_post_comment_action
   WHERE action_type = 'LIKE' AND request_id LIKE 'legacy-comment-like-%') AS assertion_ok;

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM ums_creative_square
   WHERE member_id IS NOT NULL AND data_id IS NOT NULL AND data_id > 0 AND data_type IN (1, 2, 3))
  =
  (SELECT COUNT(*) FROM community_post_reference WHERE JSON_EXTRACT(snapshot_json, '$.legacy') = TRUE)
  AS assertion_ok;

-- runner: assert=scalar-true
SELECT
  ((SELECT COUNT(*)
    FROM ums_creative_square s
    CROSS JOIN JSON_TABLE(
      CONCAT('[', CHAR(34),
        REPLACE(REPLACE(REPLACE(TRIM(s.pics), CHAR(92), CONCAT(CHAR(92), CHAR(92))), CHAR(34), CONCAT(CHAR(92), CHAR(34))),
          ';', CONCAT(CHAR(34), ',', CHAR(34))),
        CHAR(34), ']'),
      '$[*]' COLUMNS(url VARCHAR(500) PATH '$')
    ) media
    WHERE s.member_id IS NOT NULL AND s.pics IS NOT NULL AND TRIM(s.pics) <> '' AND TRIM(media.url) <> '')
   +
   (SELECT COUNT(*)
    FROM ums_creative_square s
    CROSS JOIN JSON_TABLE(
      CONCAT('[', CHAR(34),
        REPLACE(REPLACE(REPLACE(TRIM(s.file_url), CHAR(92), CONCAT(CHAR(92), CHAR(92))), CHAR(34), CONCAT(CHAR(92), CHAR(34))),
          ';', CONCAT(CHAR(34), ',', CHAR(34))),
        CHAR(34), ']'),
      '$[*]' COLUMNS(url VARCHAR(500) PATH '$')
    ) file_asset
    WHERE s.member_id IS NOT NULL AND s.file_url IS NOT NULL AND TRIM(s.file_url) <> '' AND TRIM(file_asset.url) <> '')
   +
   (SELECT COUNT(*) FROM ums_creative_square
    WHERE member_id IS NOT NULL AND route_preview_img IS NOT NULL AND TRIM(route_preview_img) <> ''))
  =
  (SELECT COUNT(*) FROM community_post_media
   WHERE upload_request_id LIKE 'legacy-square-media-%'
      OR upload_request_id LIKE 'legacy-square-file-%'
      OR upload_request_id LIKE 'legacy-square-route-%') AS assertion_ok;

-- runner: assert=scalar-true
SELECT NOT EXISTS (
  SELECT 1
  FROM community_post_comment c
  LEFT JOIN community_post_comment parent ON parent.id = c.parent_id AND parent.post_id = c.post_id
  LEFT JOIN community_post_comment root ON root.id = c.root_id AND root.post_id = c.post_id
  WHERE c.client_request_id LIKE 'legacy-square-comment-%'
    AND c.parent_id IS NOT NULL
    AND (parent.id IS NULL OR root.id IS NULL OR root.parent_id IS NOT NULL)
) AS assertion_ok;

-- 23 张专业社区表一次性从目标库回读；缺任意一张必须红。
-- runner: assert=scalar-true
SELECT COUNT(*) = 23 AS assertion_ok
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
    'community_post','community_post_media','community_post_reference','community_post_revision',
    'community_post_member_action','community_post_command_receipt','community_post_comment','community_post_comment_action','community_post_topic','community_post_mention',
    'community_post_outbox','community_guideline_version','community_guideline_ack',
    'community_post_enforcement','community_post_appeal','community_featured_entry',
    'community_member_mute','community_notification','community_notification_preference','community_notification_actor',
    'community_member_governance_state','community_search_document','community_erasure_job'
  ) AND engine = 'InnoDB';

-- 六条核心幂等/资产唯一性必须从 MySQL 回读，应用层先查不算数据库保证。
-- runner: assert=scalar-true
SELECT COUNT(DISTINCT index_name) = 6 AS assertion_ok
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND non_unique = 0
  AND index_name IN ('uk_community_post_request','uk_community_post_member_action',
                     'uk_community_post_comment_request','uk_community_post_command_request',
                     'uk_community_post_outbox_event','uk_community_post_media_receipt');

-- 旧形状表升级必须真正补齐列、索引与类型约束，不允许 IF NOT EXISTS 假绿。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='community_report_case'
     AND ((column_name='policy_version' AND data_type='varchar' AND character_maximum_length=64)
       OR (column_name='reason_label_snapshot' AND data_type='varchar' AND character_maximum_length=100)
       OR (column_name='priority_snapshot' AND data_type='varchar' AND character_maximum_length=16)
       OR (column_name='first_response_minutes_snapshot' AND data_type='int'))) = 4
  AND
  (SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema=DATABASE()
     AND ((constraint_name='chk_community_report_priority'
           AND SHA2(LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REGEXP_REPLACE(check_clause,'[[:space:]]+',''),'`',''),'_utf8mb4',''),'\\',''),'(',''),')','')), 256)
               = SHA2('priority_snapshotin''urgent'',''standard''', 256))
       OR (constraint_name='chk_community_report_first_response'
           AND SHA2(LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REGEXP_REPLACE(check_clause,'[[:space:]]+',''),'`',''),'_utf8mb4',''),'\\',''),'(',''),')','')), 256)
               = SHA2('first_response_minutes_snapshotbetween1and10080', 256)))) = 2
  AND
  (SELECT COUNT(*) FROM (
     SELECT index_name, non_unique FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='community_report_timeline'
       AND index_name='idx_community_report_first_response'
     GROUP BY index_name, non_unique
     HAVING non_unique=1
        AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
            = 'actor_type,event_type,report_id,create_time'
   ) report_first_response_index) = 1
  AND
  (SELECT COUNT(*) FROM (
     SELECT index_name, non_unique FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='biz_event_log'
       AND index_name='idx_biz_event_occurred_at'
     GROUP BY index_name, non_unique
     HAVING non_unique=1
        AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')='occurred_at'
   ) biz_event_occurred_index) = 1
  AND
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='community_notification' AND column_name='comment_id') = 1
  AND
  (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='community_notification'
     AND index_name IN ('uk_community_notification_event','uk_community_notification_aggregate',
                        'idx_community_notification_inbox','idx_community_notification_post',
                        'idx_community_notification_comment','idx_community_notification_actor')) = 6
  AND
  (SELECT COUNT(*) FROM information_schema.table_constraints
   WHERE constraint_schema=DATABASE()
     AND ((table_name='community_post_outbox' AND constraint_name='chk_community_post_outbox_aggregate')
       OR (table_name='community_notification' AND constraint_name='chk_community_notification_type'))) = 2
  AND
  (SELECT COUNT(*) FROM information_schema.referential_constraints
   WHERE constraint_schema=DATABASE()
     AND table_name='community_notification_actor'
     AND constraint_name='fk_community_notification_actor_parent'
     AND referenced_table_name='community_notification'
     AND delete_rule='CASCADE') = 1
  AND
  (SELECT COUNT(*) FROM information_schema.key_column_usage
   WHERE constraint_schema=DATABASE() AND table_name='community_notification_actor'
     AND constraint_name='fk_community_notification_actor_parent'
     AND ((ordinal_position=1 AND column_name='recipient_member_id' AND referenced_column_name='recipient_member_id')
       OR (ordinal_position=2 AND column_name='notification_type' AND referenced_column_name='notification_type')
       OR (ordinal_position=3 AND column_name='aggregate_key' AND referenced_column_name='aggregate_key'))) = 3
  AS assertion_ok;

-- 社区公约必须真实存在且生效，不能只在 App 写一页静态文案。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok
FROM community_guideline_version
WHERE version_code = 'CITY_EXPLORER_1' AND status = 'ACTIVE' AND effective_at <= NOW()
  AND JSON_LENGTH(content_json, '$.rules') = 7
  AND JSON_SEARCH(content_json, 'one', 'SAFETY', NULL, '$.rules[*].code') IS NOT NULL;

-- 规范确认、发帖、处罚、申诉四段证据链必须由数据库外键闭合。
-- runner: assert=scalar-true
SELECT COUNT(*) = 4 AS assertion_ok
FROM information_schema.referential_constraints
WHERE constraint_schema = DATABASE()
  AND constraint_name IN ('fk_community_guideline_ack_version','fk_community_post_guideline',
                          'fk_community_enforcement_guideline','fk_community_appeal_enforcement');
