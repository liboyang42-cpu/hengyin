-- U08-03 创作者维护工作台：问题 → 原内容 → 修复草稿 → 复测 → 新版本 → 关闭。
-- 问题来源是 U08-02 的 play_run_feedback（玩家对自己那一局的地点/题目反馈，带原局冻结版本与修订）。
-- 一张维护单 = 作者对自己主题开的一条维护记录：链接若干反馈、记下开单时的已发布版本（原内容基线）；
-- 修复草稿 / 新版本 / 复测由现有事实推导（cms_topic.config_version、published_config_version、作者本人的
-- topic_route_session），不另存状态；关闭要么有修复证据（已发布版本超过基线），要么写明原因。
-- 不承载金额、收益或结算字段；修复新版本不改旧局（旧局按自己冻结的快照运行）。只新增表，不改既有行。
CREATE TABLE IF NOT EXISTS creator_maintenance_ticket (
  id BIGINT NOT NULL AUTO_INCREMENT,
  topic_id BIGINT NOT NULL,
  author_member_id BIGINT NOT NULL COMMENT '开单时的主题作者（cms_topic.member_id）',
  title VARCHAR(80) NOT NULL,
  note VARCHAR(500) NULL,
  baseline_config_version BIGINT NOT NULL COMMENT '开单时已发布版本（原内容）',
  baseline_revision_id BIGINT NULL COMMENT '开单时最新 cms_topic_content_revision.id；查不到为 NULL',
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
  resolution VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  fixed_config_version BIGINT NULL COMMENT 'FIXED 关闭时的已发布版本（修复证据）',
  close_reason VARCHAR(500) NULL,
  version BIGINT NOT NULL DEFAULT 1,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  close_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  close_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  closed_time DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_creator_ticket_request (author_member_id, request_id),
  KEY idx_creator_ticket_topic (topic_id, status, id),
  KEY idx_creator_ticket_author (author_member_id, status, id),
  CONSTRAINT ck_creator_ticket_status CHECK (status IN ('OPEN', 'CLOSED')),
  CONSTRAINT ck_creator_ticket_resolution CHECK (
    (status = 'OPEN' AND resolution IS NULL AND closed_time IS NULL)
    OR (status = 'CLOSED' AND resolution = 'FIXED' AND fixed_config_version IS NOT NULL AND fixed_config_version > baseline_config_version)
    OR (status = 'CLOSED' AND resolution = 'NO_CHANGE' AND close_reason IS NOT NULL AND CHAR_LENGTH(close_reason) > 0)),
  CONSTRAINT ck_creator_ticket_version CHECK (version > 0 AND baseline_config_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='U08-03 创作者维护单';

CREATE TABLE IF NOT EXISTS creator_maintenance_ticket_feedback (
  ticket_id BIGINT NOT NULL,
  feedback_id BIGINT NOT NULL COMMENT 'play_run_feedback.id；一条反馈只进一张维护单',
  PRIMARY KEY (ticket_id, feedback_id),
  UNIQUE KEY uk_creator_ticket_feedback (feedback_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='U08-03 维护单 ↔ 玩家反馈';
