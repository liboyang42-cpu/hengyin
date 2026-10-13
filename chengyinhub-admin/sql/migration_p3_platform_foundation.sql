-- P3 平台化基础设施：行为事件、漏斗、统一审核、推荐画像与评分
-- 可重复执行；历史业务表不在本迁移中修改。

CREATE TABLE IF NOT EXISTS biz_event_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  event_name VARCHAR(80) NOT NULL COMMENT '事件名称',
  member_id BIGINT NULL COMMENT '会员ID',
  anonymous_id VARCHAR(80) NULL COMMENT '匿名ID',
  session_id VARCHAR(80) NULL COMMENT '会话ID',
  source VARCHAR(80) NULL COMMENT '来源',
  page_path VARCHAR(255) NULL COMMENT '页面路径',
  biz_type VARCHAR(40) NULL COMMENT '业务类型',
  biz_id BIGINT NULL COMMENT '业务ID',
  city_code VARCHAR(40) NULL COMMENT '城市编码',
  properties_json TEXT NULL COMMENT '扩展属性JSON',
  idempotency_key VARCHAR(120) NULL COMMENT '幂等键',
  occurred_at DATETIME NOT NULL COMMENT '发生时间',
  create_time DATETIME NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_biz_event_name_time (event_name, occurred_at),
  KEY idx_biz_event_member_time (member_id, occurred_at),
  KEY idx_biz_event_biz_time (biz_type, biz_id, occurred_at),
  UNIQUE KEY uk_biz_event_idem (event_name, member_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3行为事件日志';

CREATE TABLE IF NOT EXISTS biz_funnel_daily (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  stat_date DATE NOT NULL COMMENT '统计日期',
  funnel_code VARCHAR(60) NOT NULL COMMENT '漏斗编码',
  step_code VARCHAR(60) NOT NULL COMMENT '步骤编码',
  source VARCHAR(80) NULL COMMENT '来源',
  city_code VARCHAR(40) NULL COMMENT '城市编码',
  user_count BIGINT NOT NULL DEFAULT 0 COMMENT '用户数',
  event_count BIGINT NOT NULL DEFAULT 0 COMMENT '事件数',
  conversion_rate DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT '转化率',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_funnel_daily (stat_date, funnel_code, step_code, source, city_code),
  KEY idx_funnel_daily_code_date (funnel_code, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3每日漏斗聚合';

CREATE TABLE IF NOT EXISTS audit_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  biz_type VARCHAR(40) NOT NULL COMMENT '业务类型',
  biz_id BIGINT NOT NULL COMMENT '业务ID',
  submitter_id BIGINT NULL COMMENT '提交人会员ID',
  source_type VARCHAR(40) NULL COMMENT '来源类型',
  risk_level VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT '风险等级',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0待审 1通过 2驳回 3升级 4取消',
  current_assignee BIGINT NULL COMMENT '当前处理人',
  submitted_at DATETIME NOT NULL COMMENT '提交时间',
  deadline_at DATETIME NULL COMMENT '处理截止时间',
  snapshot_json MEDIUMTEXT NULL COMMENT '提交快照',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  create_by VARCHAR(64) NULL COMMENT '创建者',
  create_time DATETIME NULL COMMENT '创建时间',
  update_by VARCHAR(64) NULL COMMENT '更新者',
  update_time DATETIME NULL COMMENT '更新时间',
  remark VARCHAR(500) NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_audit_biz_version (biz_type, biz_id, version),
  KEY idx_audit_status_deadline (status, deadline_at),
  KEY idx_audit_submitter (submitter_id, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3统一审核任务';

CREATE TABLE IF NOT EXISTS audit_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_id BIGINT NOT NULL COMMENT '审核任务ID',
  action VARCHAR(40) NOT NULL COMMENT '动作',
  operator_id BIGINT NULL COMMENT '操作人ID',
  reason_code VARCHAR(60) NULL COMMENT '原因编码',
  reason_text VARCHAR(500) NULL COMMENT '原因说明',
  snapshot_json MEDIUMTEXT NULL COMMENT '审核快照',
  create_time DATETIME NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_audit_record_task (task_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3统一审核记录';

CREATE TABLE IF NOT EXISTS member_interest_profile (
  member_id BIGINT NOT NULL COMMENT '会员ID',
  city_code VARCHAR(40) NULL COMMENT '城市编码',
  category_weights_json TEXT NULL COMMENT '分类权重JSON',
  creator_weights_json TEXT NULL COMMENT '创作者权重JSON',
  last_event_at DATETIME NULL COMMENT '最近行为时间',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (member_id),
  KEY idx_interest_city (city_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3会员兴趣画像';

CREATE TABLE IF NOT EXISTS recommendation_score_daily (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  stat_date DATE NOT NULL COMMENT '统计日期',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  candidate_type VARCHAR(40) NOT NULL COMMENT '候选类型',
  candidate_id BIGINT NOT NULL COMMENT '候选ID',
  score DECIMAL(12,4) NOT NULL DEFAULT 0.0000 COMMENT '推荐分',
  reason_codes VARCHAR(500) NULL COMMENT '推荐原因编码',
  rank_position INT NULL COMMENT '排序位置',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_reco_daily_candidate (stat_date, member_id, candidate_type, candidate_id),
  KEY idx_reco_daily_rank (member_id, candidate_type, stat_date, rank_position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3每日推荐评分';

-- P3 后台菜单与权限。挂载在“系统管理”下，后续可在后台调整父级。
SET @p3_parent := (
  SELECT menu_id FROM sys_menu WHERE menu_name = 'P3平台运营' LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT 'P3平台运营', 0, 50, 'p3-platform', NULL, '', '', 1, 0, 'M', '0', '0', '', 'dashboard', 'admin', sysdate(), '', NULL, 'P3平台运营目录'
WHERE @p3_parent IS NULL;

SET @p3_parent := (
  SELECT menu_id FROM sys_menu WHERE menu_name = 'P3平台运营' LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '增长漏斗', @p3_parent, 1, 'analytics', 'business/analytics/index', '', '', 1, 0, 'C', '0', '0', 'business:analytics:list', 'chart', 'admin', sysdate(), '', NULL, 'P3增长漏斗'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:analytics:list');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '审核工作台', @p3_parent, 2, 'audit', 'business/audit/index', '', '', 1, 0, 'C', '0', '0', 'business:audit:list', 'validCode', 'admin', sysdate(), '', NULL, 'P3统一审核'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:audit:list');

SET @audit_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:audit:list' LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '审核处理', @audit_menu, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:audit:review', '#', 'admin', sysdate(), '', NULL, 'P3审核处理按钮'
WHERE @audit_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:audit:review');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '推荐配置', @p3_parent, 3, 'recommendation', 'business/recommendation/index', '', '', 1, 0, 'C', '0', '0', 'business:recommendation:list', 'tree', 'admin', sysdate(), '', NULL, 'P3推荐系统'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:recommendation:list');

SET @reco_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:recommendation:list' LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '推荐权重配置', @reco_menu, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:recommendation:config', '#', 'admin', sysdate(), '', NULL, 'P3推荐配置按钮'
WHERE @reco_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:recommendation:config');
