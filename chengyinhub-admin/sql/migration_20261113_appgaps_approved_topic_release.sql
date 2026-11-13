-- App 已批准主题发行（/api/approved-topic-release/v1/*，App Core/ApprovedTopicRelease*.swift、ApprovedTopicReview*.swift）。
-- approved_topic_review_submission：作者从 App 送审时冻结的「审核捕获」（只含可公开字段，不含答案/商家指引），
--   连同送审前观察到的任务 (id, version)、送审后任务 (id, version, status) 与送审包 snapshot_json 的 sha256。
--   同一 (member, request_id) 只送审一次；重放读回原回执。
-- approved_topic_release：已批准快照的不可变发行分配。revision_no 是本主题发行头（CAS：expectedHeadRevision+1），
--   同一已批准快照 (audit_task_id, audit_task_version, manifest_hash) 只分配一次。发行记录不改变公开可见性或玩家资格。
-- 只存业务字段，不存令牌或口令。
CREATE TABLE IF NOT EXISTS approved_topic_review_submission (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 topic_id BIGINT NOT NULL,
 member_id BIGINT NOT NULL,
 request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 observed_task_id BIGINT NOT NULL,
 observed_task_version INT NOT NULL,
 source_config_version BIGINT NOT NULL,
 snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 audit_task_id BIGINT NOT NULL,
 submitted_task_version INT NOT NULL,
 submitted_task_status TINYINT NOT NULL,
 bundle_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 capture_json MEDIUMTEXT NOT NULL,
 create_time DATETIME(3) NOT NULL,
 UNIQUE KEY uk_atr_review_request(member_id,request_id),
 KEY idx_atr_review_task(audit_task_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS approved_topic_release (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 topic_id BIGINT NOT NULL,
 member_id BIGINT NOT NULL,
 request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision_no BIGINT NOT NULL,
 source_config_version BIGINT NOT NULL,
 audit_task_id BIGINT NOT NULL,
 audit_record_id BIGINT NOT NULL,
 audit_task_version INT NOT NULL,
 schema_version INT NOT NULL,
 manifest_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 audit_snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 review_submission_id BIGINT NOT NULL,
 manifest_json MEDIUMTEXT NOT NULL,
 create_time DATETIME(3) NOT NULL,
 UNIQUE KEY uk_atr_release_request(topic_id,request_id),
 UNIQUE KEY uk_atr_release_revision(topic_id,revision_no),
 UNIQUE KEY uk_atr_release_snapshot(audit_task_id,audit_task_version,manifest_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- runner: assert=scalar-true
SELECT COUNT(*)=2 FROM information_schema.statistics WHERE table_schema=DATABASE()
 AND ((table_name='approved_topic_review_submission' AND index_name='uk_atr_review_request' AND seq_in_index=2 AND column_name='request_id')
   OR (table_name='approved_topic_release' AND index_name='uk_atr_release_snapshot' AND seq_in_index=3 AND column_name='manifest_hash'));
