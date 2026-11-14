-- 商家缺口包（App be-gap-merchant 29 行）：只建新表，不改既有表与数据，可重跑。
-- 1) W05 商家 AI 草稿 → 生成 → 人审发布（cursor/w05-j-merchant-authoring 的候选表，转为正式迁移）。
-- 2) merchant_authoring_source：W05 草稿来源（MERCHANT_AI_TEMPLATE_SOURCE_V1）到个人模板的不可变引用，供 draft-selection 只读。
-- 3) merchant_npc_knowledge_review：门店分身知识送审原请求（requestId 幂等，进平台审核单）。
-- 4) merchant_npc_avatar_job：门店分身形象生成任务。
-- 5) merchant_offer_withdrawal_request：章节供给退出原请求回执（requestId 幂等，结果可回读）。
-- 6) merchant_city_value_report / _snapshot / _fact：CITY 商家价值报告、事件快照与冻结事实。
-- 不涉及资金、库存、结算或支付。

CREATE TABLE IF NOT EXISTS w05_authoring_job (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_id BIGINT NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL,
  merchant_id BIGINT NOT NULL,
  theme_id BIGINT NULL,
  draft_id VARCHAR(64) NULL,
  product_id BIGINT NULL,
  sku_id BIGINT NULL,
  template_id VARCHAR(64) NULL,
  fact_version VARCHAR(64) NULL,
  prompt_version VARCHAR(64) NULL,
  model_version VARCHAR(64) NULL,
  cost_ref VARCHAR(64) NULL,
  output_hash CHAR(64) NULL,
  error_code VARCHAR(64) NULL,
  status VARCHAR(16) NOT NULL,
  scene VARCHAR(32) NULL,
  run_id VARCHAR(64) NULL,
  binding_key VARCHAR(32) NULL,
  expected_version INT NULL,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_w05_job_request (owner_id, request_id),
  KEY idx_w05_job_merchant (merchant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W05 商家 AI 生成任务（先 CLAIMED 再调网关）';

CREATE TABLE IF NOT EXISTS w05_authoring_draft (
  id VARCHAR(64) NOT NULL,
  owner_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  theme_id BIGINT NULL,
  product_id BIGINT NULL,
  sku_id BIGINT NULL,
  template_id VARCHAR(64) NULL,
  scene VARCHAR(32) NULL,
  run_id VARCHAR(64) NULL,
  binding_key VARCHAR(32) NOT NULL,
  fact_version VARCHAR(64) NOT NULL,
  current_version INT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  PRIMARY KEY (id),
  KEY idx_w05_draft_merchant (merchant_id, created_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W05 商家草稿（版本号 CAS）';

CREATE TABLE IF NOT EXISTS w05_authoring_revision (
  id BIGINT NOT NULL AUTO_INCREMENT,
  draft_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  body_json TEXT NOT NULL,
  body_hash CHAR(64) NOT NULL,
  fact_hash CHAR(64) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  created_by BIGINT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_w05_revision (draft_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W05 草稿版本（只插入）';

CREATE TABLE IF NOT EXISTS w05_authoring_approval (
  id BIGINT NOT NULL AUTO_INCREMENT,
  draft_id VARCHAR(64) NOT NULL,
  revision_id BIGINT NOT NULL,
  revision_version INT NOT NULL,
  kind VARCHAR(32) NOT NULL,
  actor_id BIGINT NOT NULL,
  subject_hash CHAR(64) NOT NULL,
  audit_task_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at_ms BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_w05_approval (draft_id, revision_version, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W05 本店事实确认 / 主题内容批准';

CREATE TABLE IF NOT EXISTS w05_authoring_release (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_id BIGINT NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL,
  draft_id VARCHAR(64) NOT NULL,
  revision_id BIGINT NOT NULL,
  revision_version INT NOT NULL,
  merchant_id BIGINT NOT NULL,
  theme_id BIGINT NULL,
  run_id VARCHAR(64) NULL,
  binding_key VARCHAR(32) NOT NULL,
  snapshot_json TEXT NOT NULL,
  snapshot_hash CHAR(64) NOT NULL,
  fact_hash CHAR(64) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  visibility VARCHAR(64) NULL,
  published_at_ms BIGINT NULL,
  withdrawn_at_ms BIGINT NULL,
  withdraw_request_id VARCHAR(64) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_w05_release_request (owner_id, request_id),
  KEY idx_w05_release_public (merchant_id, theme_id, status, id),
  KEY idx_w05_release_draft (draft_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W05 不可变发布快照（撤回只改状态）';

CREATE TABLE IF NOT EXISTS w05_environment_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  release_id BIGINT NOT NULL,
  provider VARCHAR(64) NULL,
  source VARCHAR(64) NOT NULL,
  observed_at_ms BIGINT NULL,
  expires_at_ms BIGINT NULL,
  timezone VARCHAR(64) NULL,
  accuracy VARCHAR(32) NULL,
  branch VARCHAR(32) NOT NULL,
  weather VARCHAR(32) NULL,
  action VARCHAR(16) NOT NULL,
  store_hours VARCHAR(64) NULL,
  created_at_ms BIGINT NOT NULL,
  PRIMARY KEY (id),
  KEY idx_w05_env_release (release_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W05 发布时的环境分支记录';

CREATE TABLE IF NOT EXISTS merchant_authoring_source (
  id BIGINT NOT NULL AUTO_INCREMENT,
  candidate_id BIGINT NOT NULL,
  owner_member_id BIGINT NOT NULL,
  merchant_row_id BIGINT NOT NULL,
  actual_template_id BIGINT NOT NULL,
  template_snapshot_json LONGTEXT NOT NULL,
  template_content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  confirmation_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  source_json LONGTEXT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_authoring_source_candidate (candidate_id),
  UNIQUE KEY uk_authoring_source_template (actual_template_id),
  KEY idx_authoring_source_owner (owner_member_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W05 草稿来源 → 个人模板（MERCHANT_AI_TEMPLATE_SOURCE_V1）';

CREATE TABLE IF NOT EXISTS merchant_npc_knowledge_review (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL COMMENT '提交人（当前登录会员）',
  merchant_id BIGINT NOT NULL COMMENT 'mms_merchant.id',
  request_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  name VARCHAR(64) NOT NULL,
  greeting VARCHAR(500) NOT NULL,
  persona TEXT NOT NULL,
  knowledge TEXT NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT 'PENDING_REVIEW',
  audit_task_id BIGINT NULL,
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_npc_knowledge_request (member_id, request_id),
  KEY idx_npc_knowledge_merchant (merchant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店分身知识送审原请求';

CREATE TABLE IF NOT EXISTS merchant_npc_avatar_job (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  image_url VARCHAR(1024) NOT NULL,
  style VARCHAR(32) NOT NULL,
  provider VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT 'PENDING/SUCCEEDED/FAILED',
  model_url VARCHAR(1024) NULL,
  thumb_url VARCHAR(1024) NULL,
  fail_reason VARCHAR(64) NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_npc_avatar_merchant (merchant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店分身形象生成任务';

CREATE TABLE IF NOT EXISTS merchant_offer_withdrawal_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL COMMENT 'mms_merchant.id',
  owner_id BIGINT NOT NULL COMMENT 'mms_merchant.member_id',
  offer_id BIGINT NOT NULL,
  request_id CHAR(36) CHARACTER SET ascii NOT NULL,
  outcome VARCHAR(24) NOT NULL,
  intent_sha256 CHAR(64) CHARACTER SET ascii NULL,
  received_sha256 CHAR(64) CHARACTER SET ascii NULL,
  expected_sha256 CHAR(64) CHARACTER SET ascii NULL,
  reason_sha256 CHAR(64) CHARACTER SET ascii NULL,
  received_reason_sha256 CHAR(64) CHARACTER SET ascii NULL,
  topic_id BIGINT NULL,
  chapter_id BIGINT NULL,
  status_at_write INT NULL,
  required_at_write INT NULL,
  user_status_at_write INT NULL,
  offer_updated_at BIGINT NULL,
  completed_at BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_offer_withdrawal_request (actor_id, merchant_id, offer_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='章节供给退出原请求回执';

CREATE TABLE IF NOT EXISTS merchant_city_value_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  request_id VARCHAR(96) NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  region_id VARCHAR(96) NOT NULL,
  rules_release_id VARCHAR(96) NOT NULL,
  body_json LONGTEXT NOT NULL,
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_value_report_request (merchant_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CITY 商家价值报告（同请求号回放原正文）';

CREATE TABLE IF NOT EXISTS merchant_city_value_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  scope_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  salt CHAR(64) CHARACTER SET ascii NOT NULL,
  fact_count INT NOT NULL,
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_city_value_snapshot_merchant (merchant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CITY 商家价值事件快照（迟到事件需要新快照）';

CREATE TABLE IF NOT EXISTS merchant_city_value_fact (
  id BIGINT NOT NULL AUTO_INCREMENT,
  snapshot_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  kind VARCHAR(24) NOT NULL,
  occurred_at VARCHAR(32) NOT NULL,
  visit_id VARCHAR(96) NOT NULL,
  service_id VARCHAR(96) NULL,
  service_type VARCHAR(48) NULL,
  reason_code VARCHAR(32) NULL,
  rating INT NULL,
  PRIMARY KEY (id),
  KEY idx_city_value_fact_snapshot (snapshot_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CITY 商家价值冻结事实（来自到访与反馈）';
