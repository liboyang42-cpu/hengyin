-- P3 Phase B：玩家成长、IP世界观、创作者中心
-- 可重复执行；不修改首页、发布流程、结算或提现状态。

CREATE TABLE IF NOT EXISTS member_growth (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  level_no INT NOT NULL DEFAULT 1 COMMENT '等级',
  exp_value INT NOT NULL DEFAULT 0 COMMENT '经验值',
  energy_value INT NOT NULL DEFAULT 0 COMMENT '能量值',
  city_score INT NOT NULL DEFAULT 0 COMMENT '城市探索分',
  last_active_time DATETIME NULL COMMENT '最近活跃时间',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_growth_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3玩家成长账户';

CREATE TABLE IF NOT EXISTS growth_mission (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  mission_code VARCHAR(80) NOT NULL COMMENT '任务编码',
  mission_name VARCHAR(120) NOT NULL COMMENT '任务名称',
  mission_desc VARCHAR(255) NULL COMMENT '任务说明',
  trigger_event VARCHAR(80) NOT NULL COMMENT '触发事件',
  exp_reward INT NOT NULL DEFAULT 0 COMMENT '经验奖励',
  energy_reward INT NOT NULL DEFAULT 0 COMMENT '能量奖励',
  daily_limit INT NOT NULL DEFAULT 1 COMMENT '每日上限',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_growth_mission_code (mission_code),
  KEY idx_growth_mission_event (trigger_event, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3成长任务';

CREATE TABLE IF NOT EXISTS growth_mission_progress (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  mission_code VARCHAR(80) NOT NULL COMMENT '任务编码',
  biz_type VARCHAR(40) NOT NULL DEFAULT '' COMMENT '业务类型',
  biz_id BIGINT NOT NULL DEFAULT 0 COMMENT '业务ID',
  progress_date DATE NOT NULL COMMENT '进度日期',
  complete_count INT NOT NULL DEFAULT 1 COMMENT '完成次数',
  reward_exp INT NOT NULL DEFAULT 0 COMMENT '已发经验',
  reward_energy INT NOT NULL DEFAULT 0 COMMENT '已发能量',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_growth_progress_idem (member_id, mission_code, biz_type, biz_id, progress_date),
  KEY idx_growth_progress_member_date (member_id, progress_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3成长任务进度';

CREATE TABLE IF NOT EXISTS growth_badge (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  badge_code VARCHAR(80) NOT NULL COMMENT '徽章编码',
  badge_name VARCHAR(120) NOT NULL COMMENT '徽章名称',
  badge_desc VARCHAR(255) NULL COMMENT '徽章说明',
  icon_url VARCHAR(255) NULL COMMENT '图标',
  unlock_rule VARCHAR(255) NULL COMMENT '解锁规则',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_growth_badge_code (badge_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3成长徽章';

CREATE TABLE IF NOT EXISTS member_badge (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  badge_code VARCHAR(80) NOT NULL COMMENT '徽章编码',
  unlock_time DATETIME NOT NULL COMMENT '解锁时间',
  source_type VARCHAR(40) NULL COMMENT '来源类型',
  source_id BIGINT NULL COMMENT '来源ID',
  create_time DATETIME NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_badge (member_id, badge_code),
  KEY idx_member_badge_member (member_id, unlock_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3会员徽章';

CREATE TABLE IF NOT EXISTS ip_world (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  world_code VARCHAR(80) NOT NULL COMMENT '世界编码',
  world_name VARCHAR(120) NOT NULL COMMENT '世界名称',
  subtitle VARCHAR(160) NULL COMMENT '副标题',
  city_code VARCHAR(40) NULL COMMENT '城市编码',
  cover_url VARCHAR(255) NULL COMMENT '封面',
  intro VARCHAR(500) NULL COMMENT '简介',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态',
  deleted CHAR(1) NOT NULL DEFAULT '0' COMMENT '删除标记',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ip_world_code (world_code),
  KEY idx_ip_world_city_status (city_code, status, deleted, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3 IP世界';

CREATE TABLE IF NOT EXISTS ip_city_chapter (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  world_id BIGINT NOT NULL COMMENT '世界ID',
  chapter_name VARCHAR(120) NOT NULL COMMENT '章节名称',
  city_code VARCHAR(40) NULL COMMENT '城市编码',
  cover_url VARCHAR(255) NULL COMMENT '封面',
  intro VARCHAR(500) NULL COMMENT '简介',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_ip_chapter_world (world_id, status, sort_no),
  KEY idx_ip_chapter_city (city_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3城市章节';

CREATE TABLE IF NOT EXISTS ip_character (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  world_id BIGINT NOT NULL COMMENT '世界ID',
  character_name VARCHAR(120) NOT NULL COMMENT '角色名称',
  avatar_url VARCHAR(255) NULL COMMENT '头像',
  intro VARCHAR(500) NULL COMMENT '简介',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_ip_character_world (world_id, status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3 IP角色';

CREATE TABLE IF NOT EXISTS ip_quest (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  world_id BIGINT NOT NULL COMMENT '世界ID',
  chapter_id BIGINT NULL COMMENT '章节ID',
  quest_name VARCHAR(120) NOT NULL COMMENT '任务名称',
  quest_type VARCHAR(40) NOT NULL DEFAULT 'explore' COMMENT '任务类型',
  target_biz_type VARCHAR(40) NULL COMMENT '目标业务类型',
  target_biz_id BIGINT NULL COMMENT '目标业务ID',
  reward_exp INT NOT NULL DEFAULT 0 COMMENT '奖励经验',
  reward_energy INT NOT NULL DEFAULT 0 COMMENT '奖励能量',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_ip_quest_world (world_id, status, sort_no),
  KEY idx_ip_quest_target (target_biz_type, target_biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3世界任务';

CREATE TABLE IF NOT EXISTS creator_profile (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  creator_name VARCHAR(120) NOT NULL COMMENT '创作者名称',
  bio VARCHAR(500) NULL COMMENT '简介',
  avatar_url VARCHAR(255) NULL COMMENT '头像',
  apply_status TINYINT NOT NULL DEFAULT 0 COMMENT '申请状态：0待审 1通过 2驳回',
  reviewer_id BIGINT NULL COMMENT '审核人',
  review_reason VARCHAR(500) NULL COMMENT '审核原因',
  reviewed_at DATETIME NULL COMMENT '审核时间',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_creator_member (member_id),
  KEY idx_creator_status (apply_status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3创作者档案';

CREATE TABLE IF NOT EXISTS creator_metric_daily (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  stat_date DATE NOT NULL COMMENT '统计日期',
  creator_id BIGINT NOT NULL COMMENT '创作者ID',
  content_count INT NOT NULL DEFAULT 0 COMMENT '内容数',
  view_count BIGINT NOT NULL DEFAULT 0 COMMENT '浏览数',
  like_count BIGINT NOT NULL DEFAULT 0 COMMENT '点赞数',
  favorite_count BIGINT NOT NULL DEFAULT 0 COMMENT '收藏数',
  signup_count BIGINT NOT NULL DEFAULT 0 COMMENT '报名数',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_creator_metric_daily (stat_date, creator_id),
  KEY idx_creator_metric_creator (creator_id, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3创作者每日指标';

CREATE TABLE IF NOT EXISTS creator_income_detail (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  creator_id BIGINT NOT NULL COMMENT '创作者ID',
  source_type VARCHAR(40) NOT NULL COMMENT '来源类型',
  source_id BIGINT NOT NULL COMMENT '来源ID',
  amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '金额',
  income_status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0待结算 1已计入 2已取消',
  occur_time DATETIME NOT NULL COMMENT '发生时间',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_creator_income_creator (creator_id, occur_time),
  KEY idx_creator_income_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3创作者收益明细';

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
SELECT '玩家成长', @p3_parent, 4, 'growth', 'business/growth/index', '', '', 1, 0, 'C', '0', '0', 'business:growth:list', 'peoples', 'admin', sysdate(), '', NULL, 'P3玩家成长'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:growth:list');

SET @growth_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:growth:list' LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '成长配置', @growth_menu, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:growth:edit', '#', 'admin', sysdate(), '', NULL, 'P3成长配置按钮'
WHERE @growth_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:growth:edit');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT 'IP世界观', @p3_parent, 5, 'world', 'business/world/index', '', '', 1, 0, 'C', '0', '0', 'business:world:list', 'international', 'admin', sysdate(), '', NULL, 'P3 IP世界观'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:world:list');

SET @world_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:world:list' LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '世界观配置', @world_menu, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:world:edit', '#', 'admin', sysdate(), '', NULL, 'P3世界观配置按钮'
WHERE @world_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:world:edit');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '创作者中心', @p3_parent, 6, 'creator', 'business/creator/index', '', '', 1, 0, 'C', '0', '0', 'business:creator:list', 'user', 'admin', sysdate(), '', NULL, 'P3创作者中心'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:creator:list');

SET @creator_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:creator:list' LIMIT 1
);

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '创作者审核', @creator_menu, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:creator:review', '#', 'admin', sysdate(), '', NULL, 'P3创作者审核按钮'
WHERE @creator_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:creator:review');
