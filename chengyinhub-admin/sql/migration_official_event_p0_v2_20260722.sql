-- ============================================================================
-- 官方活动 P0 V2：绑定、任务、证据、承接、有效参与关系与活动 360 真源。
-- 仅新增表/列，不修改或删除 V1 event_signup 历史数据；生产执行前必须走
-- chengyin-db-migrate 的只读预检 → 用户确认 → 执行 → describe 回读。
-- ============================================================================

-- ① V2 总控字段。旧活动默认 contract_version=1，保持 content_json/progress 的兼容读取。
SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'contract_version');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN contract_version TINYINT NOT NULL DEFAULT 1 COMMENT ''1历史兼容/2证据合同'' AFTER sender_account',
  'SELECT ''official_event.contract_version 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- sender_account 的值跨 sys_user / ums_member 不可裸比较。V2 新草稿只写 UMS_MEMBER；
-- 迁移前无法判明来源的历史行保持 LEGACY_UNRESOLVED，须人工重绑，绝不按同号 ID 猜测。
SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'creator_identity_source');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN creator_identity_source VARCHAR(24) NOT NULL DEFAULT ''LEGACY_UNRESOLVED'' COMMENT ''V2创建身份域:UMS_MEMBER/LEGACY_UNRESOLVED'' AFTER sender_account',
  'SELECT ''official_event.creator_identity_source 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'fulfillment_policy');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN fulfillment_policy VARCHAR(32) NOT NULL DEFAULT ''SELF_RUN'' COMMENT ''SELF_RUN/OPTIONAL_PARTNERS/REQUIRED_FULFILLMENT'' AFTER contract_version',
  'SELECT ''official_event.fulfillment_policy 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'control_state');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN control_state VARCHAR(16) NOT NULL DEFAULT ''NORMAL'' COMMENT ''NORMAL/PAUSED'' AFTER fulfillment_policy',
  'SELECT ''official_event.control_state 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'paused_from_status');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN paused_from_status TINYINT DEFAULT NULL COMMENT ''暂停前生命周期状态'' AFTER control_state',
  'SELECT ''official_event.paused_from_status 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'paused_at');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN paused_at DATETIME DEFAULT NULL COMMENT ''暂停时间'' AFTER paused_from_status',
  'SELECT ''official_event.paused_at 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'paused_by');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN paused_by BIGINT DEFAULT NULL COMMENT ''暂停操作者'' AFTER paused_at',
  'SELECT ''official_event.paused_by 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'paused_reason');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN paused_reason VARCHAR(500) DEFAULT NULL COMMENT ''暂停原因'' AFTER paused_by',
  'SELECT ''official_event.paused_reason 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'resume_note');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN resume_note VARCHAR(500) DEFAULT NULL COMMENT ''恢复风险处理说明'' AFTER paused_reason',
  'SELECT ''official_event.resume_note 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'pause_impact_scope');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN pause_impact_scope VARCHAR(500) DEFAULT NULL COMMENT ''暂停影响范围或人数'' AFTER paused_reason',
  'SELECT ''official_event.pause_impact_scope 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'pause_alternative_plan');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN pause_alternative_plan VARCHAR(500) DEFAULT NULL COMMENT ''暂停替代方案'' AFTER pause_impact_scope',
  'SELECT ''official_event.pause_alternative_plan 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'pause_notification_plan');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN pause_notification_plan VARCHAR(500) DEFAULT NULL COMMENT ''暂停通知策略'' AFTER pause_alternative_plan',
  'SELECT ''official_event.pause_notification_plan 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event' AND column_name = 'pause_notification_status');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event ADD COLUMN pause_notification_status VARCHAR(16) DEFAULT NULL COMMENT ''暂停通知状态:PENDING/NOT_REQUIRED'' AFTER pause_notification_plan',
  'SELECT ''official_event.pause_notification_status 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② 内容/空间编排线。活动自有点不写入长期 roam_poi。
CREATE TABLE IF NOT EXISTS official_event_binding (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  binding_code VARCHAR(64) NOT NULL,
  binding_type VARCHAR(32) NOT NULL COMMENT 'THEME/ROAM_POI/ROAM_AREA/MERCHANT_NODE/CLUB_SESSION/EVENT_OWNED_POINT/DISPLAY_ONLY',
  source_mode VARCHAR(16) NOT NULL COMMENT 'REFERENCE/EVENT_OWNED',
  source_ref_id BIGINT DEFAULT NULL,
  city VARCHAR(32) DEFAULT NULL,
  latitude DECIMAL(10,6) DEFAULT NULL,
  longitude DECIMAL(10,6) DEFAULT NULL,
  radius_m INT DEFAULT NULL,
  geometry_json TEXT DEFAULT NULL,
  display_json TEXT DEFAULT NULL,
  snapshot_json TEXT DEFAULT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  sort INT NOT NULL DEFAULT 0,
  create_time DATETIME DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_binding_code (event_id, binding_code),
  UNIQUE KEY uk_event_binding_reference (event_id, binding_type, source_ref_id),
  KEY idx_event_status (event_id, status),
  KEY idx_source (binding_type, source_ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方活动V2内容与空间绑定';

-- ③ 玩家任务合同。P0 固定 required_count=1，前置任务以结构化 JSON 保存并在服务层校验。
CREATE TABLE IF NOT EXISTS official_event_mission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  binding_id BIGINT NOT NULL,
  mission_code VARCHAR(64) NOT NULL,
  mission_type VARCHAR(32) NOT NULL COMMENT 'ROAM_POI_ARRIVAL/EVENT_POINT_ARRIVAL/THEME_VERIFIED_FINISH',
  title VARCHAR(128) NOT NULL,
  short_description VARCHAR(500) DEFAULT NULL,
  rule_json TEXT NOT NULL,
  prerequisite_json TEXT DEFAULT NULL,
  rule_version VARCHAR(32) NOT NULL DEFAULT '1',
  required_count INT NOT NULL DEFAULT 1,
  visibility TINYINT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  sort INT NOT NULL DEFAULT 0,
  available_start DATETIME DEFAULT NULL,
  available_end DATETIME DEFAULT NULL,
  create_time DATETIME DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_mission_code (event_id, mission_code),
  KEY idx_event_status (event_id, status),
  KEY idx_binding (binding_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方活动V2玩家任务合同';

-- ④ 活动级责任承接线。一行代表一项结构化职责范围，绝不从 merchant_ids 自动生成。
CREATE TABLE IF NOT EXISTS official_event_party (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  party_type VARCHAR(16) NOT NULL COMMENT 'OFFICIAL/MERCHANT/CLUB',
  party_id BIGINT NOT NULL COMMENT 'OFFICIAL=ums_member.id;MERCHANT=mms_merchant.id;CLUB=club.id',
  identity_source VARCHAR(24) NOT NULL DEFAULT 'LEGACY_UNRESOLVED' COMMENT 'OFFICIAL=UMS_MEMBER;未知旧身份不参与授权',
  organizer_authority TINYINT NOT NULL DEFAULT 0 COMMENT '仅OFFICIAL/ORGANIZER:1当前唯一有效主办',
  role VARCHAR(32) NOT NULL COMMENT 'ORGANIZER/CO_ORGANIZER/FULFILLMENT_MERCHANT/FULFILLMENT_CLUB/SPONSOR/PROMOTION_PARTNER',
  target_type VARCHAR(32) DEFAULT NULL,
  target_id BIGINT DEFAULT NULL,
  window_start DATETIME DEFAULT NULL,
  window_end DATETIME DEFAULT NULL,
  capacity INT DEFAULT NULL,
  responsibility_summary VARCHAR(500) DEFAULT NULL,
  contact_role VARCHAR(64) DEFAULT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'INVITED',
  invited_by BIGINT DEFAULT NULL,
  invited_at DATETIME DEFAULT NULL,
  status_by BIGINT DEFAULT NULL,
  status_at DATETIME DEFAULT NULL,
  status_reason VARCHAR(500) DEFAULT NULL,
  snapshot_json TEXT DEFAULT NULL,
  create_time DATETIME DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_event_status (event_id, status),
  KEY idx_party (party_type, party_id),
  KEY idx_event_role (event_id, role, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方活动V2主办协办与承接关系';

-- 开发/灰度环境可能已有早期 V2 表；旧行没有身份源就只能待人工重绑，不能把 sys_user.id 猜成会员 ID。
SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event_party' AND column_name = 'identity_source');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event_party ADD COLUMN identity_source VARCHAR(24) NOT NULL DEFAULT ''LEGACY_UNRESOLVED'' COMMENT ''OFFICIAL=UMS_MEMBER;未知旧身份不参与授权'' AFTER party_id',
  'SELECT ''official_event_party.identity_source 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'official_event_party' AND column_name = 'organizer_authority');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event_party ADD COLUMN organizer_authority TINYINT NOT NULL DEFAULT 0 COMMENT ''仅OFFICIAL/ORGANIZER:1当前唯一有效主办'' AFTER identity_source',
  'SELECT ''official_event_party.organizer_authority 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 后台 sys_user 与小程序 ums_member 是两个身份域。Web V2 写入只能经此表的显式、可撤销授权，
-- 绝不以相同数字 ID 推断主体。active_sys_user_id 仅在授权有效时保留值，以唯一键保证一名后台
-- 账号任一时刻只代表一名官方会员；撤销后置空，历史行继续保留调证。
CREATE TABLE IF NOT EXISTS official_event_operator_authorization (
  id BIGINT NOT NULL AUTO_INCREMENT,
  sys_user_id BIGINT NOT NULL,
  active_sys_user_id BIGINT DEFAULT NULL,
  member_id BIGINT NOT NULL COMMENT 'ums_member.id，必须是官方发布白名单会员',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE/REVOKED',
  granted_by BIGINT NOT NULL COMMENT '授予授权的sys_user.id',
  granted_at DATETIME NOT NULL,
  grant_reason VARCHAR(500) NOT NULL,
  revoked_by BIGINT DEFAULT NULL,
  revoked_at DATETIME DEFAULT NULL,
  revoke_reason VARCHAR(500) DEFAULT NULL,
  create_time DATETIME DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_active_sys_user (active_sys_user_id),
  KEY idx_member_status (member_id, status),
  KEY idx_sys_user_history (sys_user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台官方活动操作员到会员主体的显式授权';

-- ⑤ REQUIRED_FULFILLMENT 的发布门槛，需求与承接范围使用相同目标/时间/角色/容量维度。
CREATE TABLE IF NOT EXISTS official_event_fulfillment_requirement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  mission_id BIGINT DEFAULT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id BIGINT NOT NULL,
  window_start DATETIME DEFAULT NULL,
  window_end DATETIME DEFAULT NULL,
  required_role VARCHAR(32) NOT NULL,
  required_capacity INT NOT NULL DEFAULT 0,
  critical TINYINT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  create_time DATETIME DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_event_status (event_id, status),
  KEY idx_mission (mission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方活动V2履约覆盖需求';

-- ⑥ V2 有效参与关系；event_signup 仅保存历史兼容报名投影。
CREATE TABLE IF NOT EXISTS official_event_participation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  signup_id BIGINT DEFAULT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/LEFT/REFUNDED/BANNED',
  reason VARCHAR(500) DEFAULT NULL,
  joined_at DATETIME NOT NULL,
  invalidated_at DATETIME DEFAULT NULL,
  create_time DATETIME DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_member (event_id, member_id),
  KEY idx_event_state (event_id, state),
  KEY idx_member_state (member_id, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方活动V2有效参与关系';

-- ⑦ 到达验证事实：不留原始坐标，只存可调证的最小判定快照。
CREATE TABLE IF NOT EXISTS event_arrival_verification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  mission_id BIGINT NOT NULL,
  binding_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  session_id BIGINT DEFAULT NULL,
  verification_type VARCHAR(32) NOT NULL,
  request_id VARCHAR(80) NOT NULL,
  distance_m INT DEFAULT NULL,
  accuracy_bucket VARCHAR(32) DEFAULT NULL,
  rule_version VARCHAR(32) NOT NULL,
  result VARCHAR(16) NOT NULL COMMENT 'ACCEPTED/REJECTED',
  risk_code VARCHAR(64) DEFAULT NULL,
  occurred_at DATETIME NOT NULL,
  create_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_mission_member_request (event_id, mission_id, member_id, request_id),
  KEY idx_member_time (member_id, occurred_at),
  KEY idx_event_result (event_id, result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方活动到达验证事实';

-- ⑧ 证据真源。唯一键同时挡网络重试与同一来源事实重复归因。
CREATE TABLE IF NOT EXISTS official_event_mission_evidence (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  mission_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  signup_id BIGINT DEFAULT NULL,
  source_type VARCHAR(48) NOT NULL,
  source_fact_id VARCHAR(128) NOT NULL,
  dedupe_key VARCHAR(200) NOT NULL,
  status VARCHAR(20) NOT NULL COMMENT 'PENDING_REVIEW/VALID/REJECTED/REVOKED',
  reason VARCHAR(500) DEFAULT NULL,
  source_snapshot_json TEXT DEFAULT NULL,
  rule_snapshot_json TEXT DEFAULT NULL,
  occurred_at DATETIME NOT NULL,
  validated_at DATETIME DEFAULT NULL,
  revoked_at DATETIME DEFAULT NULL,
  operated_by BIGINT DEFAULT NULL,
  create_time DATETIME DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_evidence_dedupe (dedupe_key),
  UNIQUE KEY uk_evidence_source (event_id, mission_id, member_id, source_type, source_fact_id),
  UNIQUE KEY uk_evidence_source_once (source_type, source_fact_id),
  KEY idx_event_member_status (event_id, member_id, status),
  KEY idx_mission_status (mission_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方活动V2任务证据';

-- 开发/灰度环境若曾在这份迁移中断前建过表，也必须补上“一个来源事实只能归因一次”的硬约束。
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'official_event_mission_evidence'
                  AND index_name = 'uk_evidence_source_once');
SET @sql := IF(@exists = 0,
  'ALTER TABLE official_event_mission_evidence ADD UNIQUE KEY uk_evidence_source_once (source_type, source_fact_id)',
  'SELECT ''official_event_mission_evidence.uk_evidence_source_once 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ⑨ 结算/领取读模型真源，避免把 event_signup.status 当奖励投递流水。
CREATE TABLE IF NOT EXISTS official_event_reward_issuance (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  reward_rule_version VARCHAR(32) NOT NULL,
  status VARCHAR(20) NOT NULL COMMENT 'NOT_ELIGIBLE/ELIGIBLE/PENDING_ISSUE/ISSUED/CLAIMED/FAILED/MANUAL_REVIEW',
  issuance_key VARCHAR(160) NOT NULL,
  payload_json TEXT DEFAULT NULL,
  failure_reason VARCHAR(500) DEFAULT NULL,
  source_snapshot_json TEXT DEFAULT NULL,
  create_time DATETIME DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_member_rule (event_id, member_id, reward_rule_version),
  UNIQUE KEY uk_issuance_key (issuance_key),
  KEY idx_event_status (event_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方活动V2奖励投递与资格读模型';

-- ⑩ 活动操作审计：接受、拒绝、暂停、发布、撤销证据均从这里回读。
CREATE TABLE IF NOT EXISTS official_event_audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  action VARCHAR(64) NOT NULL,
  operator_id BIGINT DEFAULT NULL,
  summary VARCHAR(500) DEFAULT NULL,
  before_json TEXT DEFAULT NULL,
  after_json TEXT DEFAULT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_event_time (event_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方活动V2操作审计';

-- 当前漫游 POI 到达仍依赖设备上送坐标，未完成真实/受控样本及负控回读前必须保持关闭，
-- 以免它直接产出可自动计奖的 VALID evidence。缺键时服务端也会 fail-closed。
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '官方活动-漫游POI到达验证', 'official_event.roam_poi_arrival_enabled', 'false', 'N', 'admin', NOW(),
       'Gate 0 未完成前禁止 ROAM_POI_ARRIVAL 自动计奖；需真实/受控样本与负控回读后才可开启'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'official_event.roam_poi_arrival_enabled');

-- 后台代理授权是高风险操作，独立于普通活动编辑权限；不自动授予任何角色，
-- 仅超级管理员或后续显式获配的角色可维护该授权。
SET @official_event_menu := (SELECT menu_id FROM sys_menu
                              WHERE component = 'business/officialevent/index' AND menu_type = 'C'
                              ORDER BY menu_id LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '官方活动主办授权', @official_event_menu, 5, 'F', '0', '0', 'business:officialevent:authorize', 'admin', NOW(),
       'P0：后台账号到白名单官方会员的显式授权/撤销，默认不授予任何角色'
FROM dual
WHERE @official_event_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:officialevent:authorize');

-- 奖励投递、证据撤销与参与资格失效不能只依赖普通编辑权限；默认不授予任何角色。
INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '官方活动结算', @official_event_menu, 6, 'F', '0', '0', 'business:officialevent:settle', 'admin', NOW(),
       'P0：手动结算与奖励投递，高风险操作，默认不授予任何角色'
FROM dual
WHERE @official_event_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:officialevent:settle');

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '官方活动审计处置', @official_event_menu, 7, 'F', '0', '0', 'business:officialevent:audit', 'admin', NOW(),
       'P0：证据撤销与参与资格失效，默认不授予任何角色'
FROM dual
WHERE @official_event_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:officialevent:audit');
