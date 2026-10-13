-- ============================================================================
-- 城瘾生产库 · 全量合并迁移脚本(自动生成 2026-07-04)
-- 组成:
--   Part A - 5 个"老账"迁移(预检确认生产 ❌待落,新 jar 依赖):
--     1) migration_activity_designer_commission.sql
--     2) migration_p3_phase_b_player_world_creator.sql (建 member_growth 等 12 张表)
--     3) migration_p3_phase_c_crm_finance.sql
--     4) migration_p3_phase_c_web_rules_refund.sql
--     5) migration_p3_phase_b_retire_energy.sql (退役 energy 列,必须排在 #2 之后)
--   Part B - 已有 24 个 migration_*.sql 合并批次(内容取自 _deploy_merged_20260704.sql,原样保留)。
-- 用法:
--   1) phpMyAdmin「SQL」标签页整段粘贴执行;或
--   2) mysql 交互式会话: mysql -u xxx -p dbname < _deploy_ALL_20260704.sql
-- 安全性:
--   - 所有 ADD COLUMN / ADD INDEX / DROP COLUMN 均经 _ac / _ai / _dc 存储过程守卫,
--     列或索引已存在(或已不存在)则跳过,目标表不存在则打印 SKIP 警告(不报错、不中断)。
--   - 所有 INSERT 均已改写为 INSERT...SELECT...WHERE NOT EXISTS,按自然键去重
--     (或沿用原文件本就是幂等写法的 SELECT ... WHERE NOT EXISTS / WHERE ... IS NULL)。
--   - 所有 CREATE TABLE 均为 CREATE TABLE IF NOT EXISTS。
--   - 可安全重复执行多次(第二次起应几乎全为"已存在,跳过")。
-- ⚠️ 生产库执行前仍建议先做只读预检(各段保留了原文件的预检提示)。
-- ============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS _ac;
DROP PROCEDURE IF EXISTS _ai;
DROP PROCEDURE IF EXISTS _mod;
DROP PROCEDURE IF EXISTS _dc;

DELIMITER $$

-- _ac: ADD COLUMN 幂等守卫。列已存在→跳过;表不存在→SKIP警告;否则执行 ALTER ADD COLUMN。
CREATE PROCEDURE _ac(IN p_tbl VARCHAR(128), IN p_col VARCHAR(128), IN p_coldef VARCHAR(2000))
BEGIN
  DECLARE col_exists INT DEFAULT 0;
  DECLARE tbl_exists INT DEFAULT 0;

  SELECT COUNT(*) INTO col_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl AND COLUMN_NAME = p_col;

  IF col_exists = 0 THEN
    SELECT COUNT(*) INTO tbl_exists
      FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl;

    IF tbl_exists = 0 THEN
      SELECT CONCAT('SKIP(no table): ', p_tbl) AS warn;
    ELSE
      SET @_ac_sql = CONCAT('ALTER TABLE `', p_tbl, '` ADD COLUMN ', p_coldef);
      PREPARE _ac_stmt FROM @_ac_sql;
      EXECUTE _ac_stmt;
      DEALLOCATE PREPARE _ac_stmt;
    END IF;
  END IF;
END$$

-- _ai: ADD INDEX/UNIQUE 幂等守卫。索引已存在→跳过;表不存在→SKIP警告;否则执行 ALTER ADD。
CREATE PROCEDURE _ai(IN p_tbl VARCHAR(128), IN p_idx VARCHAR(128), IN p_idxdef VARCHAR(2000))
BEGIN
  DECLARE idx_exists INT DEFAULT 0;
  DECLARE tbl_exists INT DEFAULT 0;

  SELECT COUNT(*) INTO idx_exists
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl AND INDEX_NAME = p_idx;

  IF idx_exists = 0 THEN
    SELECT COUNT(*) INTO tbl_exists
      FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl;

    IF tbl_exists = 0 THEN
      SELECT CONCAT('SKIP(no table): ', p_tbl) AS warn;
    ELSE
      SET @_ai_sql = CONCAT('ALTER TABLE `', p_tbl, '` ADD ', p_idxdef);
      PREPARE _ai_stmt FROM @_ai_sql;
      EXECUTE _ai_stmt;
      DEALLOCATE PREPARE _ai_stmt;
    END IF;
  END IF;
END$$

-- _mod: 通用 ALTER 守卫(用于 MODIFY 等非 ADD 情形)。表不存在→SKIP警告不中断;否则执行 ALTER TABLE `tbl` <ddl>。
CREATE PROCEDURE _mod(IN p_tbl VARCHAR(128), IN p_ddl VARCHAR(2000))
BEGIN
  DECLARE tbl_exists INT DEFAULT 0;
  SELECT COUNT(*) INTO tbl_exists
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl;
  IF tbl_exists = 0 THEN
    SELECT CONCAT('SKIP(no table): ', p_tbl) AS warn;
  ELSE
    SET @_mod_sql = CONCAT('ALTER TABLE `', p_tbl, '` ', p_ddl);
    PREPARE _mod_stmt FROM @_mod_sql;
    EXECUTE _mod_stmt;
    DEALLOCATE PREPARE _mod_stmt;
  END IF;
END$$

-- _dc: DROP COLUMN 幂等守卫(新增,供"老账补·retire_energy"退役列使用)。
--      表不存在→SKIP警告;列不存在→SKIP警告;否则执行 ALTER TABLE `tbl` DROP COLUMN `col`。
CREATE PROCEDURE _dc(IN p_tbl VARCHAR(128), IN p_col VARCHAR(128))
BEGIN
  DECLARE tbl_exists INT DEFAULT 0;
  DECLARE col_exists INT DEFAULT 0;

  SELECT COUNT(*) INTO tbl_exists
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl;

  IF tbl_exists = 0 THEN
    SELECT CONCAT('SKIP(no table): ', p_tbl) AS warn;
  ELSE
    SELECT COUNT(*) INTO col_exists
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl AND COLUMN_NAME = p_col;

    IF col_exists = 0 THEN
      SELECT CONCAT('SKIP(no column): ', p_tbl, '.', p_col) AS warn;
    ELSE
      SET @_dc_sql = CONCAT('ALTER TABLE `', p_tbl, '` DROP COLUMN `', p_col, '`');
      PREPARE _dc_stmt FROM @_dc_sql;
      EXECUTE _dc_stmt;
      DEALLOCATE PREPARE _dc_stmt;
    END IF;
  END IF;
END$$

DELIMITER ;

-- ================= migration_activity_designer_commission.sql (老账补) =================
SELECT 'migration_activity_designer_commission.sql' AS running;
-- 活动三方分润迁移(商户 / 主题设计者 / 平台)
-- 配套代码:CmsActivityServiceImpl 三方结算、SettlementTask 定时任务、后台设置页可改比例
-- 依赖:ALTER 存量表 cms_setting(表不存在会 SKIP 警告)。
-- 注意:本文件假定 cms_setting 已存在 id=1 的配置行(与原文件假设一致,未做额外兜底)。

-- 1. cms_setting 新增「活动设计者佣金」与「平台账户会员ID」
CALL _ac('cms_setting','activity_designer_commission','activity_designer_commission DECIMAL(5,2) DEFAULT 0 COMMENT ''活动主题设计者佣金（百分比）'' AFTER activity_merchant_commission');
CALL _ac('cms_setting','platform_member_id','platform_member_id BIGINT NULL COMMENT ''平台账户会员ID（接收平台分润）'' AFTER activity_designer_commission');

-- 2/3. 新建平台专用账户(接收平台分润)+ 写入配置 + 设默认比例(商户80% / 设计者10% / 平台10%,后台设置页可随时改)
-- ⚠️ 特殊处理(非普通"自然键"去重):原文件用同会话 LAST_INSERT_ID() 把新建的 ums_member.id
--    回填进 cms_setting.platform_member_id,是一次性种子逻辑,不是普通行插入。
--    幂等键改为:cms_setting.platform_member_id 是否已非空(非空=已执行过初始化,整段跳过;
--    已执行过之后即便后台改了佣金比例,也不会被本脚本重复执行覆盖回默认值)。
SET @cyh_platform_member_id := (SELECT platform_member_id FROM cms_setting WHERE id = 1);

INSERT INTO ums_member (user_type, nickname, name, status, balance, point, del_flag, create_by, create_time, mark)
SELECT 1, '平台账户', '平台账户', 1, 0, 0, 0, 'system', NOW(), '系统平台分润收款账户'
FROM DUAL
WHERE @cyh_platform_member_id IS NULL;

SET @cyh_platform_member_id := IF(@cyh_platform_member_id IS NULL, LAST_INSERT_ID(), @cyh_platform_member_id);

UPDATE cms_setting
SET platform_member_id = @cyh_platform_member_id,
    activity_merchant_commission = 80,
    activity_designer_commission = 10,
    activity_platform_commission = 10
WHERE id = 1
  AND platform_member_id IS NULL;

-- 4. 配置定时任务自动结算(活动+主题)。白名单已放行 com.chengyinhub.web.task
--    cron: 每小时整点执行一次;status='0' 正常运行(如需先观察可在后台「定时任务」暂停)
--    自然键 = invoke_target
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '分润结算（活动+主题）', 'DEFAULT', 'settlementTask.run()', '0 0 * * * ?', '3', '1', '0', 'admin', NOW(), '每小时执行活动与主题分润结算'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'settlementTask.run()');

-- ================= migration_p3_phase_b_player_world_creator.sql (老账补) =================
SELECT 'migration_p3_phase_b_player_world_creator.sql' AS running;
-- P3 Phase B:玩家成长、IP世界观、创作者中心
-- 可重复执行;不修改首页、发布流程、结算或提现状态。
-- 12 张新表原文件已是 CREATE TABLE IF NOT EXISTS,原样保留;
-- 菜单 INSERT 原文件已是 SELECT ... WHERE NOT EXISTS / WHERE ... IS NULL 幂等写法(自然键=perms 或 menu_name),原样保留。

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
  apply_status TINYINT NOT NULL DEFAULT 0 COMMENT '申请状态:0待审 1通过 2驳回',
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
  income_status TINYINT NOT NULL DEFAULT 0 COMMENT '状态:0待结算 1已计入 2已取消',
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
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_name = 'P3平台运营');

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

-- ================= migration_p3_phase_c_crm_finance.sql (老账补) =================
SELECT 'migration_p3_phase_c_crm_finance.sql' AS running;
-- P3 Phase C: Merchant CRM and finance foundation.
-- Apply after P3 Phase A/B migrations.
-- 4 张新表原文件已是 CREATE TABLE IF NOT EXISTS,原样保留;
-- 菜单 INSERT/UPDATE 原文件已是 SELECT ... WHERE NOT EXISTS / 按唯一 perms 的确定性 UPDATE,原样保留。

CREATE TABLE IF NOT EXISTS `merchant_customer_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `member_id` bigint NOT NULL COMMENT '用户ID',
  `member_nickname` varchar(100) DEFAULT NULL COMMENT '用户昵称快照',
  `member_phone` varchar(32) DEFAULT NULL COMMENT '用户手机号快照',
  `registration_count` bigint NOT NULL DEFAULT 0 COMMENT '报名次数',
  `order_count` bigint NOT NULL DEFAULT 0 COMMENT '订单次数',
  `order_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '订单金额',
  `coupon_receive_count` bigint NOT NULL DEFAULT 0 COMMENT '领券次数',
  `coupon_verify_count` bigint NOT NULL DEFAULT 0 COMMENT '核销次数',
  `last_interaction_time` datetime DEFAULT NULL COMMENT '最近互动时间',
  `customer_stage` varchar(32) NOT NULL DEFAULT 'new' COMMENT '客户阶段 new/active/lost/vip',
  `city_code` varchar(32) DEFAULT NULL COMMENT '城市编码',
  `tags_json` varchar(1000) DEFAULT NULL COMMENT '标签JSON',
  `del_flag` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记 0正常 2删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_member` (`merchant_id`, `member_id`),
  KEY `idx_merchant_stage` (`merchant_id`, `customer_stage`),
  KEY `idx_merchant_last_interaction` (`merchant_id`, `last_interaction_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3商家客户画像';

CREATE TABLE IF NOT EXISTS `merchant_customer_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `tag_code` varchar(64) NOT NULL COMMENT '标签编码',
  `tag_name` varchar(64) NOT NULL COMMENT '标签名称',
  `tag_color` varchar(32) DEFAULT NULL COMMENT '标签颜色',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序',
  `del_flag` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记 0正常 2删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_tag_code` (`merchant_id`, `tag_code`),
  KEY `idx_merchant_sort` (`merchant_id`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3商家客户标签';

CREATE TABLE IF NOT EXISTS `settlement_batch` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `batch_no` varchar(64) NOT NULL COMMENT '批次号',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `period_start` datetime DEFAULT NULL COMMENT '结算周期开始',
  `period_end` datetime DEFAULT NULL COMMENT '结算周期结束',
  `total_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '结算总额',
  `detail_count` bigint NOT NULL DEFAULT 0 COMMENT '明细数',
  `success_count` bigint NOT NULL DEFAULT 0 COMMENT '成功数',
  `failed_count` bigint NOT NULL DEFAULT 0 COMMENT '失败数',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态 0待审核 1已通过 2已失败',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人ID',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_no` (`batch_no`),
  UNIQUE KEY `uk_idempotency_key` (`idempotency_key`),
  KEY `idx_merchant_status` (`merchant_id`, `status`),
  KEY `idx_period` (`period_start`, `period_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3结算批次';

CREATE TABLE IF NOT EXISTS `settlement_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `batch_id` bigint NOT NULL COMMENT '批次ID',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `source_type` varchar(32) NOT NULL COMMENT '来源类型 order/coupon/activity/topic',
  `source_id` bigint NOT NULL COMMENT '来源ID',
  `receiver_type` varchar(32) NOT NULL COMMENT '收款方类型 merchant/creator/platform',
  `receiver_id` bigint NOT NULL COMMENT '收款方ID',
  `gross_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '原始金额',
  `commission_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '佣金金额',
  `settlement_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '结算金额',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态 0待处理 1成功 2失败',
  `fail_reason` varchar(255) DEFAULT NULL COMMENT '失败原因',
  `idempotency_key` varchar(160) NOT NULL COMMENT '明细幂等键',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_detail_idempotency` (`idempotency_key`),
  UNIQUE KEY `uk_source_receiver` (`source_type`, `source_id`, `receiver_type`, `receiver_id`),
  KEY `idx_batch` (`batch_id`),
  KEY `idx_merchant_status` (`merchant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3结算明细';

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '商家CRM查询', 0, 1, '#', '', 1, 0, 'F', '0', '0', 'business:crm:list', '#', 'system', NOW(), 'P3 Phase C CRM list permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:crm:list');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '商家CRM聚合', 0, 2, '#', '', 1, 0, 'F', '0', '0', 'business:crm:aggregate', '#', 'system', NOW(), 'P3 Phase C CRM aggregate permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:crm:aggregate');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '结算查询', 0, 3, '#', '', 1, 0, 'F', '0', '0', 'business:settlement:list', '#', 'system', NOW(), 'P3 Phase C settlement list permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlement:list');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '结算生成', 0, 4, '#', '', 1, 0, 'F', '0', '0', 'business:settlement:generate', '#', 'system', NOW(), 'P3 Phase C settlement generate permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlement:generate');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '结算打款', 0, 5, '#', '', 1, 0, 'F', '0', '0', 'business:settlement:confirm', '#', 'system', NOW(), 'P3 Phase C settlement payout confirm permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlement:confirm');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '提现审核', 0, 6, '#', '', 1, 0, 'F', '0', '0', 'business:memberwithdrawal:review', '#', 'system', NOW(), 'P3 Phase C guarded withdrawal review permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:memberwithdrawal:review');

SET @p3_parent := (
  SELECT menu_id FROM sys_menu WHERE menu_name = 'P3平台运营' LIMIT 1
);

INSERT INTO sys_menu
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 'P3平台运营', 0, 50, 'p3-platform', NULL, '', '', 1, 0, 'M', '0', '0', '', 'dashboard', 'admin', sysdate(), '', NULL, 'P3平台运营目录'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_name = 'P3平台运营');

SET @p3_parent := (
  SELECT menu_id FROM sys_menu WHERE menu_name = 'P3平台运营' LIMIT 1
);

UPDATE sys_menu
SET menu_name = '商家CRM',
    parent_id = @p3_parent,
    order_num = 7,
    path = 'crm',
    component = 'business/crm/index',
    query = '',
    route_name = '',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'C',
    visible = '0',
    status = '0',
    icon = 'peoples',
    remark = 'P3商家CRM'
WHERE perms = 'business:crm:list';

INSERT INTO sys_menu
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT '商家CRM', @p3_parent, 7, 'crm', 'business/crm/index', '', '', 1, 0, 'C', '0', '0', 'business:crm:list', 'peoples', 'admin', sysdate(), '', NULL, 'P3商家CRM'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:crm:list');

SET @crm_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:crm:list' LIMIT 1
);

UPDATE sys_menu
SET parent_id = @crm_menu,
    path = '',
    component = '',
    menu_type = 'F',
    visible = '0',
    status = '0'
WHERE perms = 'business:crm:aggregate';

UPDATE sys_menu
SET menu_name = '结算管理',
    parent_id = @p3_parent,
    order_num = 8,
    path = 'settlement',
    component = 'business/settlement/index',
    query = '',
    route_name = '',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'C',
    visible = '0',
    status = '0',
    icon = 'money',
    remark = 'P3结算管理'
WHERE perms = 'business:settlement:list';

INSERT INTO sys_menu
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT '结算管理', @p3_parent, 8, 'settlement', 'business/settlement/index', '', '', 1, 0, 'C', '0', '0', 'business:settlement:list', 'money', 'admin', sysdate(), '', NULL, 'P3结算管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlement:list');

SET @settlement_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:settlement:list' LIMIT 1
);

UPDATE sys_menu
SET parent_id = @settlement_menu,
    path = '',
    component = '',
    menu_type = 'F',
    visible = '0',
    status = '0'
WHERE perms = 'business:settlement:generate';

-- ================= migration_p3_phase_c_web_rules_refund.sql (老账补) =================
SELECT 'migration_p3_phase_c_web_rules_refund.sql' AS running;
-- P3 Phase C: Web settlement rules and refund review.
-- Apply after migration_p3_phase_c_crm_finance.sql.
-- 2 张新表原文件已是 CREATE TABLE IF NOT EXISTS,原样保留;
-- INSERT/UPDATE 原文件已是 SELECT ... WHERE NOT EXISTS / 按唯一 perms 或复合自然键的确定性写法,原样保留。

CREATE TABLE IF NOT EXISTS `settlement_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_id` bigint NOT NULL DEFAULT 0 COMMENT '商家ID,0为平台默认',
  `biz_type` varchar(32) NOT NULL COMMENT '业务类型 activity/topic/coupon/order',
  `rule_name` varchar(100) DEFAULT NULL COMMENT '规则名称',
  `settlement_cycle` varchar(32) NOT NULL DEFAULT 'monthly' COMMENT '结算周期 daily/weekly/monthly/manual',
  `platform_commission_rate` decimal(10,4) NOT NULL DEFAULT 0.1000 COMMENT '平台抽成比例',
  `merchant_rate` decimal(10,4) NOT NULL DEFAULT 0.9000 COMMENT '商家比例',
  `creator_rate` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT '创作者比例',
  `inviter_rate` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT '邀请人比例',
  `min_settlement_amount` decimal(18,2) NOT NULL DEFAULT 100.00 COMMENT '最低结算金额',
  `refund_deadline_hours` int NOT NULL DEFAULT 24 COMMENT '开始前多少小时可退',
  `allow_refund_after_verified` tinyint NOT NULL DEFAULT 0 COMMENT '已核销是否可退',
  `auto_approve_refund` tinyint NOT NULL DEFAULT 0 COMMENT '是否自动通过退款',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
  `del_flag` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_scope` (`merchant_id`, `biz_type`, `status`, `del_flag`),
  KEY `idx_biz_type` (`biz_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3结算规则';

CREATE TABLE IF NOT EXISTS `refund_application` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `refund_no` varchar(64) DEFAULT NULL COMMENT '退款单号',
  `source_type` varchar(32) NOT NULL COMMENT '来源类型 registration/order/coupon',
  `source_id` bigint NOT NULL COMMENT '来源ID',
  `owner_type` bigint DEFAULT NULL COMMENT '拥有类型 1主题 2活动',
  `owner_id` bigint DEFAULT NULL COMMENT '主题/活动ID',
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `merchant_id` bigint DEFAULT NULL COMMENT '商家ID',
  `paid_amount` decimal(18,2) DEFAULT 0.00 COMMENT '支付金额',
  `refund_amount` decimal(18,2) NOT NULL DEFAULT 0.00 COMMENT '退款金额',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态 0待审核 1通过 2驳回',
  `reason` varchar(500) DEFAULT NULL COMMENT '申请原因',
  `reject_reason` varchar(500) DEFAULT NULL COMMENT '驳回/审核原因',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `adjustment_created` tinyint NOT NULL DEFAULT 0 COMMENT '是否已生成冲销',
  `idempotency_key` varchar(128) DEFAULT NULL COMMENT '幂等键',
  `del_flag` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_idempotency` (`idempotency_key`),
  KEY `idx_source` (`source_type`, `source_id`),
  KEY `idx_merchant_status` (`merchant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P3退款申请';

INSERT INTO settlement_rule
(merchant_id, biz_type, rule_name, settlement_cycle, platform_commission_rate, merchant_rate, creator_rate, inviter_rate,
 min_settlement_amount, refund_deadline_hours, allow_refund_after_verified, auto_approve_refund, status, del_flag, create_time)
SELECT 0, 'activity', '平台默认活动结算规则', 'monthly', 0.1000, 0.9000, 0.0000, 0.0000, 100.00, 24, 0, 0, 1, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'activity' AND status = 1 AND del_flag = 0);

INSERT INTO settlement_rule
(merchant_id, biz_type, rule_name, settlement_cycle, platform_commission_rate, merchant_rate, creator_rate, inviter_rate,
 min_settlement_amount, refund_deadline_hours, allow_refund_after_verified, auto_approve_refund, status, del_flag, create_time)
SELECT 0, 'topic', '平台默认主题结算规则', 'monthly', 0.1000, 0.8000, 0.1000, 0.0000, 100.00, 24, 0, 0, 1, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'topic' AND status = 1 AND del_flag = 0);

INSERT INTO settlement_rule
(merchant_id, biz_type, rule_name, settlement_cycle, platform_commission_rate, merchant_rate, creator_rate, inviter_rate,
 min_settlement_amount, refund_deadline_hours, allow_refund_after_verified, auto_approve_refund, status, del_flag, create_time)
SELECT 0, 'coupon', '平台默认优惠券核销结算规则', 'monthly', 0.0000, 1.0000, 0.0000, 0.0000, 100.00, 24, 0, 0, 1, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'coupon' AND status = 1 AND del_flag = 0);

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '结算规则查询', 0, 6, '#', '', 1, 0, 'F', '0', '0', 'business:settlementrule:list', '#', 'system', NOW(), 'P3 Phase C settlement rule list permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlementrule:list');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '结算规则编辑', 0, 7, '#', '', 1, 0, 'F', '0', '0', 'business:settlementrule:edit', '#', 'system', NOW(), 'P3 Phase C settlement rule edit permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlementrule:edit');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '退款查询', 0, 8, '#', '', 1, 0, 'F', '0', '0', 'business:refund:list', '#', 'system', NOW(), 'P3 Phase C refund list permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:refund:list');

INSERT INTO sys_menu (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
SELECT '退款审核', 0, 9, '#', '', 1, 0, 'F', '0', '0', 'business:refund:review', '#', 'system', NOW(), 'P3 Phase C refund review permission'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:refund:review');

SET @p3_parent := (
  SELECT menu_id FROM sys_menu WHERE menu_name = 'P3平台运营' LIMIT 1
);

INSERT INTO sys_menu
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 'P3平台运营', 0, 50, 'p3-platform', NULL, '', '', 1, 0, 'M', '0', '0', '', 'dashboard', 'admin', sysdate(), '', NULL, 'P3平台运营目录'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_name = 'P3平台运营');

SET @p3_parent := (
  SELECT menu_id FROM sys_menu WHERE menu_name = 'P3平台运营' LIMIT 1
);

UPDATE sys_menu
SET menu_name = '结算规则',
    parent_id = @p3_parent,
    order_num = 9,
    path = 'settlementrule',
    component = 'business/settlementrule/index',
    query = '',
    route_name = '',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'C',
    visible = '0',
    status = '0',
    icon = 'build',
    remark = 'P3结算规则'
WHERE perms = 'business:settlementrule:list';

INSERT INTO sys_menu
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT '结算规则', @p3_parent, 9, 'settlementrule', 'business/settlementrule/index', '', '', 1, 0, 'C', '0', '0', 'business:settlementrule:list', 'build', 'admin', sysdate(), '', NULL, 'P3结算规则'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:settlementrule:list');

SET @settlement_rule_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:settlementrule:list' LIMIT 1
);

UPDATE sys_menu
SET parent_id = @settlement_rule_menu,
    path = '',
    component = '',
    menu_type = 'F',
    visible = '0',
    status = '0'
WHERE perms = 'business:settlementrule:edit';

UPDATE sys_menu
SET menu_name = '退款审核',
    parent_id = @p3_parent,
    order_num = 10,
    path = 'refund',
    component = 'business/refund/index',
    query = '',
    route_name = '',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'C',
    visible = '0',
    status = '0',
    icon = 'form',
    remark = 'P3退款审核'
WHERE perms = 'business:refund:list';

INSERT INTO sys_menu
  (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT '退款审核', @p3_parent, 10, 'refund', 'business/refund/index', '', '', 1, 0, 'C', '0', '0', 'business:refund:list', 'form', 'admin', sysdate(), '', NULL, 'P3退款审核'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:refund:list');

SET @refund_menu := (
  SELECT menu_id FROM sys_menu WHERE perms = 'business:refund:list' LIMIT 1
);

UPDATE sys_menu
SET parent_id = @refund_menu,
    path = '',
    component = '',
    menu_type = 'F',
    visible = '0',
    status = '0'
WHERE perms = 'business:refund:review';

-- ================= migration_p3_phase_b_retire_energy.sql (老账补,须在 player_world_creator 之后) =================
SELECT 'migration_p3_phase_b_retire_energy.sql' AS running;
-- P3 Phase B 并池清理:退役「能量」与「探索分」
-- 价值模型统一为「经验 + 积分」;积分走 UmsMember.point + UmsMemberPointsDetail 统一账本(IPointsAwardService)。
-- 成长系统只保留经验/等级。本段用新增的 _dc 守卫改写原文件的 5 处 DROP COLUMN(列或表不存在则 SKIP,可重复执行)。

CALL _dc('member_growth','energy_value');
CALL _dc('member_growth','city_score');
CALL _dc('growth_mission','energy_reward');
CALL _dc('growth_mission_progress','reward_energy');
CALL _dc('ip_quest','reward_energy');

-- ================= migration_freeexplore_node_narrative.sql =================
SELECT 'migration_freeexplore_node_narrative.sql' AS running;
-- 自由探索(mode2)节点叙事三列。选填,空回退 description。
-- 只读预检: SELECT COLUMN_NAME FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cms_topic_node'
--     AND COLUMN_NAME IN ('hook_text','card_hook_long','fragment_text');
-- 依赖:ALTER 存量表 cms_topic_node(表不存在会 SKIP 警告)。

CALL _ac('cms_topic_node','hook_text','hook_text VARCHAR(120) NULL COMMENT ''自由探索:卡堆行一句钩子''');
CALL _ac('cms_topic_node','card_hook_long','card_hook_long VARCHAR(300) NULL COMMENT ''自由探索:地点大卡NPC 2-3句钩子''');
CALL _ac('cms_topic_node','fragment_text','fragment_text VARCHAR(600) NULL COMMENT ''自由探索:故事碎片正文(完成入手记)''');

-- ================= migration_coop_invite_terms_deadline.sql =================
SELECT 'migration_coop_invite_terms_deadline.sql' AS running;
-- coop_invite 扩列(报告§7.1 · D-1):条款字段 + 双死线字段
-- 依赖:ALTER 存量表 coop_invite(表不存在会 SKIP 警告 / MODIFY 段会硬报错,见下方 REVIEW)。

CALL _ac('coop_invite','game_id','game_id BIGINT(20) NULL COMMENT ''城市定向:目标游戏(游戏级邀约)'' AFTER topic_id');
CALL _ac('coop_invite','target_level','target_level TINYINT(1) NULL DEFAULT 0 COMMENT ''槽位粒度 0主题级/1游戏级'' AFTER game_id');
CALL _ac('coop_invite','expire_time','expire_time DATETIME NULL COMMENT ''邀约自身过期时间(=主题招募截止,挂起超时判过期)'' AFTER handle_time');
CALL _ac('coop_invite','config_deadline','config_deadline DATETIME NULL COMMENT ''自由探索:同意后配置游戏死线(超时释放槽位)'' AFTER expire_time');
CALL _ac('coop_invite','share_mode','share_mode TINYINT(1) NULL COMMENT ''报酬模式 0引流/1分成/2固定'' AFTER config_deadline');
CALL _ac('coop_invite','share_rate','share_rate DECIMAL(5,2) NULL COMMENT ''分成型比例(share_mode=1)'' AFTER share_mode');
CALL _ac('coop_invite','fixed_fee','fixed_fee DECIMAL(10,2) NULL COMMENT ''固定型每核销人头费(share_mode=2)'' AFTER share_rate');

-- status 语义扩展(仅登记新取值 4已顶替/5已过期):经 _mod 守卫,表不存在则 SKIP 不中断(不再硬报错)。
CALL _mod('coop_invite','MODIFY COLUMN status TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''状态 0待确认 1已接受 2已拒绝 3已取消 4已顶替 5已过期''');

-- 加索引:双死线两个 Job 按 (status, expire_time) / (status, config_deadline) 扫描
CALL _ai('coop_invite','idx_coop_expire','KEY idx_coop_expire (status, expire_time)');
CALL _ai('coop_invite','idx_coop_config','KEY idx_coop_config (status, config_deadline)');

-- ================= migration_topic_lifecycle_recruit.sql =================
SELECT 'migration_topic_lifecycle_recruit.sql' AS running;
-- cms_topic 扩列(报告§7.4 · D-6):招募死线 + 生命周期状态机
-- 依赖:ALTER 存量表 cms_topic(表不存在会 SKIP 警告)。

CALL _ac('cms_topic','recruit_deadline','recruit_deadline DATETIME NULL COMMENT ''招募截止时间(到期触发 TopicRecruitDeadlineTask 判定)'' AFTER merchant_sign_up_end_date');
CALL _ac('cms_topic','recruit_extend_count','recruit_extend_count TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''招募期已延长次数(最多 2 次)'' AFTER recruit_deadline');
CALL _ac('cms_topic','lifecycle','lifecycle TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''生命周期 0草稿 1招募 2定价 3售票 4进行 5结算 6关闭'' AFTER recruit_extend_count');

-- 招募到期 Job 按 (lifecycle, recruit_deadline) 扫描
CALL _ai('cms_topic','idx_topic_recruit','KEY idx_topic_recruit (lifecycle, recruit_deadline)');

-- ================= migration_coop_order_perk_credit.sql =================
SELECT 'migration_coop_order_perk_credit.sql' AS running;
-- 合作条款三表(报告§7.2/7.3/7.5 · D-3/D-4/D-5):权益申报 / 合作单快照 / 信用记录
-- 新建三表,对存量无影响;原文件已用 CREATE TABLE IF NOT EXISTS,原样保留。

-- D-3 权益申报(礼品/优惠券/折扣体验),邀约时被邀商家申报,定价引擎输入
CREATE TABLE IF NOT EXISTS coop_perk (
    id          BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    invite_id   BIGINT(20)    NOT NULL COMMENT '所属邀约',
    perk_type   TINYINT(1)    NOT NULL COMMENT '0礼品 1优惠券 2折扣体验',
    name        VARCHAR(64)   NULL COMMENT '名称',
    unit_cost   DECIMAL(10,2) NULL COMMENT '单人成本价(商家自报)',
    quota       INT           NULL COMMENT '总配额',
    redeem_rule VARCHAR(255)  NULL COMMENT '核销规则',
    valid_end   DATETIME      NULL COMMENT '有效期截止',
    create_time DATETIME      NULL,
    del_flag    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    KEY idx_perk_invite (invite_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协作权益申报(礼品/券/折扣)';

-- D-4 合作单=条款快照,同意瞬间冻结,后续结算只认合作单
CREATE TABLE IF NOT EXISTS coop_order (
    id             BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    invite_id      BIGINT(20)    NULL COMMENT '来源邀约(邀约侧候选;报名侧为空)',
    reg_id         BIGINT(20)    NULL COMMENT '来源报名(候选池报名侧;邀约侧为空)',
    topic_id       BIGINT(20)    NULL COMMENT '主题',
    game_id        BIGINT(20)    NULL COMMENT '城市定向:游戏',
    from_id        BIGINT(20)    NULL COMMENT '发起方memberId',
    to_id          BIGINT(20)    NULL COMMENT '受邀方(merchant=memberId/club=clubId)',
    to_type        VARCHAR(16)   NULL COMMENT '受邀方类型 merchant/club',
    share_mode     TINYINT(1)    NULL COMMENT '0引流 1分成 2固定',
    share_rate     DECIMAL(5,2)  NULL COMMENT '分成比例',
    fixed_fee      DECIMAL(10,2) NULL COMMENT '固定型每核销人头费',
    perks_snapshot JSON          NULL COMMENT '同意瞬间的权益快照',
    terms_version  VARCHAR(16)   NULL COMMENT '平台规则版本号',
    status         TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0生效 1作废 2已结算',
    create_time    DATETIME      NULL,
    del_flag       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    KEY idx_order_invite (invite_id),
    KEY idx_order_reg (reg_id),
    KEY idx_order_topic (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合作单(条款快照)';

-- D-5 信用记录:履约/超时/违约,主页展示近90天履约率,P3 与槽位配额挂钩
CREATE TABLE IF NOT EXISTS coop_credit_log (
    id          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    member_id   BIGINT(20)   NOT NULL COMMENT '被记账方memberId',
    event_type  TINYINT(1)   NOT NULL COMMENT '0履约 1超时未配置 2保护期外取消 3保护期内违约',
    invite_id   BIGINT(20)   NULL COMMENT '关联邀约',
    reason      VARCHAR(255) NULL COMMENT '理由/备注',
    create_time DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_credit_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协作信用记录';

-- ================= migration_ticket_subtype.sql =================
SELECT 'migration_ticket_subtype.sql' AS running;
-- oms_ticket 扩列(报告§7.4 · D-7):有人带/无人带双票子类型
-- 依赖:ALTER 存量表 oms_ticket(表不存在会 SKIP 警告)。

CALL _ac('oms_ticket','sub_type','sub_type VARCHAR(8) NULL COMMENT ''票种子类型 guided有人带/self无人带(仅mode=1城市定向)'' AFTER mode');

-- ================= migration_coop_perk_template.sql =================
SELECT 'migration_coop_perk_template.sql' AS running;
-- 商家常备权益模板(报告§5.1 · F1-3):商家维护礼品/券/折扣模板,邀约时快速引用

CREATE TABLE IF NOT EXISTS coop_perk_template (
    id           BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    merchant_id  BIGINT(20)    NOT NULL COMMENT '商家memberId(模板归属)',
    perk_type    TINYINT(1)    NOT NULL COMMENT '0礼品 1优惠券 2折扣体验',
    name         VARCHAR(64)   NOT NULL COMMENT '名称',
    unit_cost    DECIMAL(10,2) NULL COMMENT '单人成本价(商家自报)',
    quota        INT           NULL COMMENT '总配额',
    redeem_rule  VARCHAR(255)  NULL COMMENT '核销/使用规则',
    valid_end    DATETIME      NULL COMMENT '有效期截止',
    create_time  DATETIME      NULL,
    del_flag     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    KEY idx_perktpl_merchant (merchant_id, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家常备权益模板(礼品/券/折扣)';

-- ================= migration_coop_review.sql =================
SELECT 'migration_coop_review.sql' AS running;
-- 合作双向互评(报告§六 · F4-2):活动结束后发布者↔商家互评

CREATE TABLE IF NOT EXISTS coop_review (
    id          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    topic_id    BIGINT(20)   NOT NULL COMMENT '主题',
    from_id     BIGINT(20)   NOT NULL COMMENT '评价人memberId',
    to_id       BIGINT(20)   NOT NULL COMMENT '被评人memberId',
    rating      TINYINT(1)   NOT NULL COMMENT '评分 1~5',
    comment     VARCHAR(255) NULL COMMENT '评语',
    create_time DATETIME     NULL,
    del_flag    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_review (topic_id, from_id, to_id),
    KEY idx_review_to (to_id, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合作双向互评';

-- ================= migration_coop_settlement.sql =================
SELECT 'migration_coop_settlement.sql' AS running;
-- 商家结算单(报告§5.5 · B4-1):合作商家按核销分润 + T+7 打款

CREATE TABLE IF NOT EXISTS coop_settlement (
    id             BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    topic_id       BIGINT(20)    NOT NULL COMMENT '主题',
    coop_order_id  BIGINT(20)    NOT NULL COMMENT '来源合作单',
    merchant_id    BIGINT(20)    NOT NULL COMMENT '收款商家memberId(=coop_order.to_id)',
    share_mode     TINYINT(1)    NULL COMMENT '0引流 1分成 2固定(条款快照)',
    share_rate     DECIMAL(5,2)  NULL COMMENT '分成比例',
    fixed_fee      DECIMAL(10,2) NULL COMMENT '固定型每核销人头费',
    verified_heads INT           NOT NULL DEFAULT 0 COMMENT '核销人头数',
    verified_sales DECIMAL(15,2) NOT NULL DEFAULT 0 COMMENT '核销销售额(分成型用)',
    amount         DECIMAL(15,2) NOT NULL DEFAULT 0 COMMENT '商家应收',
    status         TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0待打款 1已打款 2作废',
    payable_time   DATETIME      NULL COMMENT '可打款时间(=活动结束+7天)',
    settle_time    DATETIME      NULL COMMENT '结算(挂单)时间',
    payout_time    DATETIME      NULL COMMENT '实际打款时间',
    create_time    DATETIME      NULL,
    del_flag       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0存在 2删除',
    PRIMARY KEY (id),
    KEY idx_settle_topic (topic_id),
    KEY idx_settle_merchant (merchant_id),
    KEY idx_settle_payout (status, payable_time),
    UNIQUE KEY uk_settle_order (coop_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家结算单(核销分润·T+7打款)';

-- ================= migration_coop_deadline_sysjob.sql =================
SELECT 'migration_coop_deadline_sysjob.sql' AS running;
-- 双死线两个 Quartz 定时任务自动注册(报告 B1-6 / B1-7)+ 商家结算 T+7 打款任务。
-- 原文件已是 INSERT...SELECT...WHERE NOT EXISTS 幂等写法(自然键=invoke_target),原样保留。
-- 依赖:sys_job 为 RuoYi 框架基础表,默认存在。

-- ① 邀约过期:挂起超 expire_time / 已接受超 config_deadline → status=5
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '双死线-邀约过期扫描', 'DEFAULT', 'coopInviteExpireTask.run()', '0 0/10 * * * ?', '3', '1', '0', 'coop-migrate', NOW(), 'B1-6 CoopInviteExpireTask'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'coopInviteExpireTask.run()');

-- ② 招募到期:lifecycle=1 且 recruit_deadline 到点 → 0接受回草稿(0)/≥1接受进定价(2)
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '双死线-招募到期判定', 'DEFAULT', 'topicRecruitDeadlineTask.run()', '0 0/10 * * * ?', '3', '1', '0', 'coop-migrate', NOW(), 'B1-7 TopicRecruitDeadlineTask'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'topicRecruitDeadlineTask.run()');

-- ③ 商家结算 T+7 打款(每天凌晨3点):扫到期待打款结算单 → 打款进商家余额
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '商家结算T+7打款', 'DEFAULT', 'coopSettlementPayoutTask.run()', '0 0 3 * * ?', '3', '1', '0', 'coop-migrate', NOW(), 'B4 CoopSettlementPayoutTask'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'coopSettlementPayoutTask.run()');

-- ================= migration_coupon_dedup.sql =================
SELECT 'migration_coupon_dedup.sql' AS running;
-- M0-4 发券防重 · DB 兜底唯一约束
-- ⚠️ 生产执行前(只读预检):
--   1) MySQL 版本需 >= 5.7(STORED 生成列);
--   2) 先查历史脏数据是否已违反唯一性,否则 ADD UNIQUE 会失败:
--      SELECT member_id, coupon_type, DATE_FORMAT(create_time,'%Y%m%d%H%i') m, COUNT(*) c
--        FROM sms_coupon WHERE del_flag = 0
--        GROUP BY member_id, coupon_type, m HAVING c > 1;
--      如有冲突,先清理/软删重复行再执行下面的唯一索引 CALL。
-- 依赖:ALTER 存量表 sms_coupon(表不存在会 SKIP 警告)。

-- 1) 分钟粒度生成列(承载表达式,MySQL < 8.0 不能直接对函数建唯一索引)
CALL _ac('sms_coupon','publish_minute','publish_minute VARCHAR(16) GENERATED ALWAYS AS (DATE_FORMAT(create_time, ''%Y%m%d%H%i'')) STORED COMMENT ''M0-4 发券分钟(生成列),用于发券防重唯一键''');

-- 2) 唯一键:商家 + 券类型 + 分钟
CALL _ai('sms_coupon','uk_coupon_dedup','UNIQUE KEY uk_coupon_dedup (member_id, coupon_type, publish_minute)');

-- ================= migration_m9_play_attempt_log.sql =================
SELECT 'migration_m9_play_attempt_log.sql' AS running;
-- M9 埋点与学习飞轮地基 · 节点级尝试日志(服务端,不依赖客户端)

CREATE TABLE IF NOT EXISTS play_attempt_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  member_id    BIGINT       NULL,
  topic_id     BIGINT       NULL,
  node_id      BIGINT       NULL,
  activity_id  BIGINT       NULL,
  attempt_type TINYINT      NULL COMMENT '1答题 2扫码 3GPS 4拍照',
  result       TINYINT      NULL COMMENT '1过 0错 2放弃',
  wrong_answer VARCHAR(120) NULL COMMENT '答错时用户答案(供AI出题评估)',
  duration_s   INT          NULL COMMENT '本次尝试用时(秒,可空)',
  created_at   DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_tn (topic_id, node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M9 节点级游玩尝试日志';

-- 周报视图:每节点 通过率 / 平均尝试 / 放弃率 / 平均用时(可玩性 Lint 原料)
CREATE OR REPLACE VIEW v_node_quality AS
SELECT
  topic_id,
  node_id,
  COUNT(*)                                                           AS attempt_cnt,
  SUM(CASE WHEN result = 1 THEN 1 ELSE 0 END)                        AS pass_cnt,
  SUM(CASE WHEN result = 0 THEN 1 ELSE 0 END)                        AS wrong_cnt,
  SUM(CASE WHEN result = 2 THEN 1 ELSE 0 END)                        AS abandon_cnt,
  ROUND(SUM(CASE WHEN result = 1 THEN 1 ELSE 0 END) / COUNT(*), 4)   AS pass_rate,
  ROUND(SUM(CASE WHEN result = 2 THEN 1 ELSE 0 END) / COUNT(*), 4)   AS abandon_rate,
  ROUND(AVG(duration_s), 1)                                          AS avg_duration_s,
  COUNT(DISTINCT member_id)                                          AS uniq_member_cnt
FROM play_attempt_log
GROUP BY topic_id, node_id;

-- ================= migration_m10_feature_flags.sql =================
SELECT 'migration_m10_feature_flags.sql' AS running;
-- M10 灰度功能开关 · 初始化 sys_config 6 行(默认全关)。自然键 = config_key。
-- 依赖:sys_config 为 RuoYi 框架基础表,默认存在。

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-自玩票', 'feature.flag.selfPlay', 'false', 'N', 'admin', NOW(), 'M10 自玩票全链,默认关'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.selfPlay');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-点亮城市', 'feature.flag.citymap', 'false', 'N', 'admin', NOW(), 'M10 点亮城市+徽章图鉴,默认关'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.citymap');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-途中彩蛋', 'feature.flag.eggs', 'false', 'N', 'admin', NOW(), 'M10 NPC彩蛋池,默认关'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.eggs');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-随机掉落', 'feature.flag.drops', 'false', 'N', 'admin', NOW(), 'M10 随机奖励掉落,默认关'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.drops');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-轨迹记录', 'feature.flag.trackRecord', 'false', 'N', 'admin', NOW(), 'M10 以走代编轨迹记录,默认关'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.trackRecord');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-XP预算', 'feature.flag.xpBudget', 'false', 'N', 'admin', NOW(), 'M10 XP预算与勋章分配,默认关'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.xpBudget');

-- ================= migration_m2_self_play.sql =================
SELECT 'migration_m2_self_play.sql' AS running;
-- M2 自玩票全链 · 数据基础
-- 依赖:ALTER 存量表 player_node_progress / cms_registration / cms_topic(表不存在会 SKIP 警告)。

-- 1) 节点进度:支持自玩会话(activity_id=0)+ 主题维度键;唯一键含 activity_id 隔离"自玩/场次"两条独立进度
CALL _ac('player_node_progress','topic_id','topic_id BIGINT NOT NULL DEFAULT 0 COMMENT ''M2 自玩:主题ID(0=旧活动会话)''');
CALL _ai('player_node_progress','uk_progress','UNIQUE KEY uk_progress (member_id, topic_id, node_id, activity_id)');
-- 若 activity_id 原为 NOT NULL 无默认,按需(原文件建议,未确认是否需要执行,保留为注释):
-- ALTER TABLE player_node_progress MODIFY activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '0=自玩会话';

-- 2) 报名/通行证:activity_id=0 + topic_id = 主题通行证;expires_at = 购后有效期(默认 90 天)
CALL _ac('cms_registration','topic_id','topic_id BIGINT NULL COMMENT ''M2 自玩:主题通行证的主题ID(配合 owner_type/activity_id=0)''');
CALL _ac('cms_registration','expires_at','expires_at DATETIME NULL COMMENT ''M2 自玩:通行证有效期(购后90天)''');

-- 3) 主题:自玩开关 + 自玩价 + 自玩票数(仅「城市定向」mode=1 主题有意义;自由探索一人无自玩票)
CALL _ac('cms_topic','self_play','self_play TINYINT DEFAULT 0 COMMENT ''M2 是否开放自玩票 0否1是(仅城市定向)''');
CALL _ac('cms_topic','self_play_price','self_play_price DECIMAL(10,2) NULL COMMENT ''M2 自玩票价格(元)''');
CALL _ac('cms_topic','self_play_quota','self_play_quota INT NULL COMMENT ''M2 自玩票数/配额(NULL 或 <=0 = 不限)''');

-- 4) 分账规则:原文件本段为示例注释(未给出确定的 settlement_rule 列名),不构成可执行语句,不纳入本次迁移。
-- settlement_rule 若为"每场景一行"结构,插入 scene=self_play 规则行(比例同 activity 默认);
-- 具体列名以生产 settlement_rule 为准,示例(需按实际调整):
-- INSERT INTO settlement_rule (scene, ratio_json, status, remark, create_time)
--   SELECT 'self_play', ratio_json, status, 'M2 自玩长尾分成(同场次比例)', NOW()
--   FROM settlement_rule WHERE scene = 'activity' LIMIT 1;

-- ================= migration_m1_3_activity_club.sql =================
SELECT 'migration_m1_3_activity_club.sql' AS running;
-- M1-3 普通活动收编俱乐部 · 活动挂 club_id
-- 依赖:ALTER 存量表 cms_activity(表不存在会 SKIP 警告)。

CALL _ac('cms_activity','club_id','club_id BIGINT NULL COMMENT ''M1-3 社群活动所属俱乐部ID(有值=社群活动,公域不出);NULL=公开活动''');

-- ================= migration_m6_reward_drop.sql =================
SELECT 'migration_m6_reward_drop.sql' AS running;
-- M6 随机奖励掉落 · 规则表 + 掉落流水
-- 依赖:sys_config 为 RuoYi 框架基础表,默认存在。

CREATE TABLE IF NOT EXISTS reward_drop_rule (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  scope       TINYINT      NULL COMMENT '0=全局 1=指定主题',
  topic_id    BIGINT       NULL COMMENT 'scope=1 时指定主题',
  drop_type   TINYINT      NULL COMMENT '1=XP倍率 2=徽章碎片 3=商家彩蛋券',
  weight      INT          NULL COMMENT '按权重抽取',
  param_json  VARCHAR(200) NULL COMMENT '类型参数:{"mult":2} / {"badgeCode":"x"} / {"couponTemplateId":1}',
  status      TINYINT      NULL COMMENT '1=启用 0=停用',
  create_time DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_scope_topic (scope, topic_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M6 随机掉落规则';

CREATE TABLE IF NOT EXISTS reward_drop_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  member_id   BIGINT       NULL,
  topic_id    BIGINT       NULL,
  node_id     BIGINT       NULL,
  activity_id BIGINT       NULL,
  drop_type   TINYINT      NULL,
  param_json  VARCHAR(200) NULL,
  created_at  DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_drop (member_id, topic_id, node_id, activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M6 掉落流水(uk 保证每节点最多掉一次)';

-- 基础概率配置(basis point,1500=15%);灰度开关复用 M10 feature.flag.drops。自然键 = config_key。
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '随机掉落-基础概率(万分)', 'reward.drop.base.prob.bp', '1500', 'N', 'admin', NOW(), 'M6 掉落基础概率,万分数(1500=15%)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'reward.drop.base.prob.bp');

-- 示例全局规则(原文件为可选示例注释,上线按运营配,不纳入本次迁移):
-- INSERT INTO reward_drop_rule (scope, topic_id, drop_type, weight, param_json, status, create_time) VALUES
--   (0, NULL, 1, 60, '{"mult":2}', 1, NOW()),
--   (0, NULL, 2, 30, '{"badgeCode":"FRAGMENT"}', 1, NOW()),
--   (0, NULL, 3, 10, '{"couponTemplateId":0}', 1, NOW());

-- ================= migration_m7_xp_budget.sql =================
SELECT 'migration_m7_xp_budget.sql' AS running;
-- M7 XP 预算与勋章分配(双轨隔离:XP 只入 member_growth.exp_value,绝不入 ums_member_points_detail)
-- 依赖:ALTER 存量表 cms_topic / cms_member_template / cms_topic_node(表不存在会 SKIP 警告)。

-- 主题 XP 预算(Σ节点xp ≤ 预算;未分配余额=通关奖励)
CALL _ac('cms_topic','xp_budget','xp_budget INT DEFAULT 3000 COMMENT ''M7 主题探索值预算''');

-- 游戏节点 XP 挂模板层;纯停靠节点挂节点层;读取时合并(节点优先→模板→全局兜底)
CALL _ac('cms_member_template','xp_value','xp_value INT NULL COMMENT ''M7 该游戏节点探索值''');
CALL _ac('cms_topic_node','xp_value','xp_value INT NULL COMMENT ''M7 纯停靠节点探索值''');

-- 等级配置(exp→level)
CREATE TABLE IF NOT EXISTS growth_level_config (
  level    INT         NOT NULL COMMENT '等级',
  need_exp INT         NULL COMMENT '达到本级所需累计探索值',
  title    VARCHAR(20) NULL COMMENT '等级称号',
  PRIMARY KEY (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M7 成长等级配置';

-- 默认等级梯度(可按运营改)。自然键 = level(主键)。
INSERT INTO growth_level_config (level, need_exp, title)
SELECT 1, 0, '新手探索者'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM growth_level_config WHERE level = 1);

INSERT INTO growth_level_config (level, need_exp, title)
SELECT 2, 300, '城市漫游者'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM growth_level_config WHERE level = 2);

INSERT INTO growth_level_config (level, need_exp, title)
SELECT 3, 800, '街巷通'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM growth_level_config WHERE level = 3);

INSERT INTO growth_level_config (level, need_exp, title)
SELECT 4, 1800, '定向老手'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM growth_level_config WHERE level = 4);

INSERT INTO growth_level_config (level, need_exp, title)
SELECT 5, 3500, '城市玩家'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM growth_level_config WHERE level = 5);

INSERT INTO growth_level_config (level, need_exp, title)
SELECT 6, 6000, '探索大师'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM growth_level_config WHERE level = 6);

INSERT INTO growth_level_config (level, need_exp, title)
SELECT 7, 10000, '城市传说'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM growth_level_config WHERE level = 7);

-- P0.5 通关勋章(fabu 配置,必配语义=后端默认"主题名·通关"兜底)
CALL _ac('cms_topic','finish_medal_name','finish_medal_name VARCHAR(40) NULL COMMENT ''P0.5 通关勋章名(空=默认 主题名·通关)''');
CALL _ac('cms_topic','finish_medal_img','finish_medal_img VARCHAR(255) NULL COMMENT ''P0.5 通关勋章图''');

-- ================= migration_m8_track_draft.sql =================
SELECT 'migration_m8_track_draft.sql' AS running;
-- M8 以走代编 · 轨迹草稿

CREATE TABLE IF NOT EXISTS track_draft (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  member_id     BIGINT       NULL,
  name          VARCHAR(60)  NULL,
  polyline      TEXT         NULL COMMENT '抽稀后轨迹(前端可直接渲染)',
  distance_m    INT          NULL COMMENT '距离(米)',
  duration_s    INT          NULL COMMENT '用时(秒)',
  waypoint_json JSON         NULL COMMENT '停靠点[{lat,lng,name,photo,note,seq}]',
  raw_oss_url   VARCHAR(255) NULL COMMENT '原始点>5000时转存 OSS 的 url',
  lit_blocks    INT          NULL COMMENT '点亮区块数(M4)',
  status        TINYINT      NULL COMMENT '0草稿 1已转路线 2已删',
  created_at    DATETIME     NULL,
  updated_at    DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_member (member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M8 轨迹草稿';

-- ================= migration_m5_npc_egg.sql =================
SELECT 'migration_m5_npc_egg.sql' AS running;
-- M5 途中彩蛋池 npc_route_egg(NPC 台词缓存 npc_line_cache 已存在,本迁移只加彩蛋表)

CREATE TABLE IF NOT EXISTS npc_route_egg (
  id         BIGINT        NOT NULL AUTO_INCREMENT,
  topic_id   BIGINT        NULL,
  seq        INT           NULL COMMENT '沿路顺序',
  lat        DECIMAL(10,6) NULL,
  lng        DECIMAL(10,6) NULL,
  radius_m   INT           DEFAULT 120 COMMENT '触发半径(米)',
  text       VARCHAR(300)  NULL COMMENT '彩蛋文案(≤80字,预审入库)',
  status     TINYINT       NULL COMMENT '0待审 1已审',
  created_at DATETIME      NULL,
  PRIMARY KEY (id),
  KEY idx_topic (topic_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M5 途中彩蛋池(每主题≤15条)';

-- ================= migration_club_lead.sql =================
SELECT 'migration_club_lead.sql' AS running;
-- P1 城市定向 · 俱乐部带领(集合/行进/结算;队长控章节+轮询,不上 WebSocket)

CREATE TABLE IF NOT EXISTS club_lead_state (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  activity_id        BIGINT       NOT NULL,
  leader_member_id   BIGINT       NOT NULL,
  current_chapter_id BIGINT       DEFAULT NULL COMMENT '当前已放行章节(队长控)',
  broadcast_text     VARCHAR(200) DEFAULT NULL,
  broadcast_at       DATETIME     DEFAULT NULL,
  status             TINYINT      DEFAULT 1 COMMENT '1=集合中 2=行进 3=已结算',
  create_time        DATETIME     DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_activity (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P1 带领场状态';

CREATE TABLE IF NOT EXISTS club_lead_checkin (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  activity_id BIGINT   NOT NULL,
  member_id   BIGINT   NOT NULL,
  arrived_at  DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_arrive (activity_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P1 集合签到(幂等)';

-- ================= migration_order_close_job.sql =================
SELECT 'migration_order_close_job.sql' AS running;
-- 过期未支付订单自动关单 定时任务注册(P0-10 弃单释放库存)
-- 原文件已是 INSERT...SELECT...WHERE NOT EXISTS 幂等写法(自然键=invoke_target),原样保留。
-- 依赖:含 OrderCloseTask 的新 jar 已部署;默认 status='1'(暂停),jar 部署完成后在后台启用。

INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '过期未支付订单关单', 'DEFAULT', 'orderCloseTask.closeExpired()', '0 0/5 * * * ?', '3', '1', '1', 'admin', NOW(),
       'P0-10 弃单关单:pay_expire_time 已过仍待支付→关单+释放库存+退积分;默认暂停,jar 部署后启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'orderCloseTask.closeExpired()');

-- ================= migration_npc_profile.sql =================
SELECT 'migration_npc_profile.sql' AS running;
-- 城瘾 AI NPC 陪伴系统: NPC 人设表
-- 作用域: scope_type 1=剧本角色(挂activity) 2=门店分身(挂merchant) 3=全局城瘾NPC
-- 原文件 CREATE TABLE 缺少 IF NOT EXISTS,已按规则补齐;其余列定义原样保留。

CREATE TABLE IF NOT EXISTS npc_profile (
  id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
  scope_type      TINYINT      NOT NULL COMMENT '1=剧本角色(挂activity) 2=门店分身(挂merchant) 3=全局城瘾NPC',
  scope_id        BIGINT       NOT NULL DEFAULT 0 COMMENT 'activityId 或 merchantId;全局NPC固定0',
  name            VARCHAR(32)  NOT NULL COMMENT 'NPC 名,如 老周/店长阿喵/小瘾',
  avatar          VARCHAR(255) COMMENT '头像 URL(阿里云 OSS)',
  persona         TEXT         NOT NULL COMMENT '人设系统提示词(性格/语气/背景/边界)',
  greeting        VARCHAR(255) COMMENT '默认开场白(兜底)',
  sort            INT          DEFAULT 0 COMMENT '同一活动多个NPC的出场顺序(主角0/配角1)',
  audit_status    TINYINT      DEFAULT 0 COMMENT '0=待审 1=通过 2=驳回',
  enabled         TINYINT      DEFAULT 1,
  del_flag        CHAR(1)      DEFAULT '0',
  create_time     DATETIME,
  update_time     DATETIME,
  KEY idx_scope (scope_type, scope_id, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI NPC 人设';

-- ================= migration_npc_line_cache.sql =================
SELECT 'migration_npc_line_cache.sql' AS running;
-- 城瘾 AI NPC 陪伴系统: 事件冒泡话术缓存(预生成+人审)
-- 原文件 CREATE TABLE 缺少 IF NOT EXISTS,已按规则补齐;其余列定义原样保留。

CREATE TABLE IF NOT EXISTS npc_line_cache (
  id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  profile_id    BIGINT       NOT NULL COMMENT '关联 npc_profile.id',
  event_type    VARCHAR(32)  NOT NULL COMMENT 'enter/checkin_success/answer_wrong/answer_right/near_merchant/activity_finish',
  node_id       BIGINT       DEFAULT NULL COMMENT '可选:绑定到具体节点(同事件按节点出不同话)',
  variants_json TEXT         NOT NULL COMMENT '同一事件的多条话术变体[](随机取一条防重复)',
  audit_status  TINYINT      DEFAULT 0 COMMENT '0=待审 1=通过(只有通过的才会下发)',
  create_time   DATETIME,
  update_time   DATETIME,
  KEY idx_lookup (profile_id, event_type, node_id, audit_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NPC 事件话术缓存(离线预生成+人审)';

-- ================= migration_npc_chat_log.sql =================
SELECT 'migration_npc_chat_log.sql' AS running;
-- 城瘾 AI NPC 陪伴系统: 追问对话日志(审计+优化语料)
-- 原文件 CREATE TABLE 缺少 IF NOT EXISTS,已按规则补齐;其余列定义原样保留。

CREATE TABLE IF NOT EXISTS npc_chat_log (
  id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT       NOT NULL,
  profile_id    BIGINT       NOT NULL,
  activity_id   BIGINT       NOT NULL COMMENT '记忆作用域',
  user_msg      TEXT         NOT NULL,
  npc_reply     TEXT,
  safety_flag   TINYINT      DEFAULT 0 COMMENT '0=正常 1=输入被拦 2=输出被拦 3=降级兜底',
  model         VARCHAR(32)  COMMENT '实际命中的模型',
  latency_ms    INT,
  token_in      INT,
  token_out     INT,
  create_time   DATETIME,
  KEY idx_user_activity (user_id, activity_id),
  KEY idx_safety (safety_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NPC 追问日志(审计与后续RAG/微调语料)';

-- ================= migration_topic_audio.sql =================
SELECT 'migration_topic_audio.sql' AS running;
-- 主题音频: cms_topic 增加 audio_url / audio_duration
-- 与模板层 cms_template_library / cms_member_template 对齐
-- 依赖:ALTER 存量表 cms_topic(表不存在会 SKIP 警告)。

CALL _ac('cms_topic','audio_url','audio_url VARCHAR(500) NULL COMMENT ''主题音频URL(OSS)''');
CALL _ac('cms_topic','audio_duration','audio_duration INT NULL COMMENT ''主题音频时长(秒)''');

-- ================= 收尾 =================
SELECT 'ALL MIGRATIONS DONE' AS running;

DROP PROCEDURE IF EXISTS _ac;
DROP PROCEDURE IF EXISTS _ai;
DROP PROCEDURE IF EXISTS _mod;
DROP PROCEDURE IF EXISTS _dc;
