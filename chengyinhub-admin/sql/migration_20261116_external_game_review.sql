-- 外部游戏（H5 包）提交与审核闭环：提交 → PENDING → 通过/驳回/升级/撤回；超时（默认 3 天）自动升级为 ESCALATED。
-- 类别：② 新增表（只新增，不改既有行）。与 App 端 Core/ExternalGameImport.swift 和
-- Core/QmodSourceSubmissionContracts.swift 的状态名一致：PENDING / APPROVED / REJECTED / ESCALATED / CANCELLED。
-- 不承载金额：monetization 只能是 NONE（CHECK 约束兜底）。签名由服务端按环境密钥签发，App 不持有密钥。
-- 每次状态变化在 external_game_review_event 追加一行（AUTHOR / ADMIN / SYSTEM），不改不删。
CREATE TABLE IF NOT EXISTS external_game (
  game_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '清单 id；首个提交者即所有者',
  owner_member_id BIGINT NOT NULL,
  approved_submission_id BIGINT NULL COMMENT '当前上架版本（最近一次 APPROVED）',
  approved_time DATETIME(3) NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (game_id),
  KEY idx_external_game_owner (owner_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部游戏标识与上架版本';

CREATE TABLE IF NOT EXISTS external_game_submission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  game_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  author_member_id BIGINT NOT NULL,
  version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(80) NOT NULL,
  creator_label VARCHAR(80) NULL COMMENT '清单 author（展示用）',
  summary VARCHAR(500) NULL,
  content_rating VARCHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  entry VARCHAR(240) NOT NULL,
  permissions_json VARCHAR(1024) NOT NULL,
  allowed_domains_json VARCHAR(3000) NOT NULL,
  package_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  package_bytes BIGINT NOT NULL,
  file_count INT NOT NULL,
  monetization VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE',
  signature CHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端 Ed25519 签名（hex）',
  signature_algorithm VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  signing_key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reject_reason VARCHAR(500) NULL,
  escalation_reason VARCHAR(500) NULL,
  escalation_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  previous_submission_id BIGINT NULL COMMENT '重新提交时指向被驳回/撤回的那次',
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  open_game_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (IF(status IN ('PENDING', 'ESCALATED'), game_id, NULL)) STORED COMMENT '同一游戏最多一个进行中的提交',
  reviewer_id BIGINT NULL COMMENT 'sys_user.user_id',
  submitted_time DATETIME(3) NOT NULL,
  reviewed_time DATETIME(3) NULL,
  escalated_time DATETIME(3) NULL,
  update_time DATETIME(3) NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_egs_game_version (game_id, version),
  UNIQUE KEY uk_egs_open_game (open_game_id),
  UNIQUE KEY uk_egs_request (author_member_id, request_id),
  KEY idx_egs_author (author_member_id, id),
  KEY idx_egs_status (status, submitted_time, id),
  CONSTRAINT ck_egs_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'ESCALATED', 'CANCELLED')),
  CONSTRAINT ck_egs_money CHECK (monetization = 'NONE'),
  CONSTRAINT ck_egs_reject CHECK (status <> 'REJECTED' OR (reject_reason IS NOT NULL AND CHAR_LENGTH(reject_reason) > 0)),
  CONSTRAINT ck_egs_escalated CHECK (status <> 'ESCALATED' OR (escalated_time IS NOT NULL AND escalation_source IN ('TIMEOUT', 'REVIEWER'))),
  CONSTRAINT ck_egs_sizes CHECK (package_bytes > 0 AND package_bytes <= 10485760 AND file_count BETWEEN 1 AND 200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部游戏提交（审核状态机）';

CREATE TABLE IF NOT EXISTS external_game_package (
  submission_id BIGINT NOT NULL,
  archive_json LONGBLOB NOT NULL COMMENT 'App 包格式 {manifest(含服务端签名), files(base64)}',
  PRIMARY KEY (submission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部游戏包内容';

CREATE TABLE IF NOT EXISTS external_game_review_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  submission_id BIGINT NOT NULL,
  from_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  to_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id BIGINT NULL COMMENT 'AUTHOR=会员 id；ADMIN=sys_user id；SYSTEM=NULL',
  reason VARCHAR(500) NULL,
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_egre_submission (submission_id, id),
  CONSTRAINT ck_egre_actor CHECK (actor_type IN ('AUTHOR', 'ADMIN', 'SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部游戏审核留痕（只追加）';

CREATE TABLE IF NOT EXISTS external_game_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  game_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  submission_id BIGINT NULL COMMENT '目录里对应的版本；侧载包为 NULL',
  version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  package_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  reporter_member_id BIGINT NOT NULL,
  reason_code VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  detail VARCHAR(500) NULL,
  blocked TINYINT(1) NOT NULL DEFAULT 0 COMMENT '举报时玩家是否同时在本机屏蔽',
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
  open_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (IF(status = 'OPEN', CONCAT(reporter_member_id, ':', game_id), NULL)) STORED,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_egr_request (reporter_member_id, request_id),
  UNIQUE KEY uk_egr_open (open_key),
  KEY idx_egr_game (game_id, status, id),
  KEY idx_egr_status (status, id),
  CONSTRAINT ck_egr_reason CHECK (reason_code IN ('INAPPROPRIATE_CONTENT', 'MALICIOUS_CODE', 'PAYMENT_OR_SCAM', 'COPYRIGHT', 'BROKEN', 'OTHER')),
  CONSTRAINT ck_egr_status CHECK (status IN ('OPEN', 'RESOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='玩家对外部游戏的举报';
