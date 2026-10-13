-- ============================================================================
-- M7 XP 预算与勋章分配(双轨隔离:XP 只入 member_growth.exp_value,绝不入 ums_member_points_detail)
-- ⚠️ 生产执行前走 chengyin-db-migrate 只读预检。
-- ============================================================================

-- 主题 XP 预算(Σ节点xp ≤ 预算;未分配余额=通关奖励)
ALTER TABLE cms_topic ADD COLUMN xp_budget INT DEFAULT 3000 COMMENT 'M7 主题探索值预算';

-- 游戏节点 XP 挂模板层;纯停靠节点挂节点层;读取时合并(节点优先→模板→全局兜底)
ALTER TABLE cms_member_template ADD COLUMN xp_value INT NULL COMMENT 'M7 该游戏节点探索值';
ALTER TABLE cms_topic_node ADD COLUMN xp_value INT NULL COMMENT 'M7 纯停靠节点探索值';

-- 等级配置(exp→level)
CREATE TABLE IF NOT EXISTS growth_level_config (
  level    INT         NOT NULL COMMENT '等级',
  need_exp INT         NULL COMMENT '达到本级所需累计探索值',
  title    VARCHAR(20) NULL COMMENT '等级称号',
  PRIMARY KEY (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M7 成长等级配置';

-- 默认等级梯度(可按运营改)
INSERT INTO growth_level_config (level, need_exp, title) VALUES
  (1, 0,     '新手探索者'),
  (2, 300,   '城市漫游者'),
  (3, 800,   '街巷通'),
  (4, 1800,  '定向老手'),
  (5, 3500,  '城市玩家'),
  (6, 6000,  '探索大师'),
  (7, 10000, '城市传说');

-- P0.5 通关勋章(fabu 配置,必配语义=后端默认"主题名·通关"兜底)
ALTER TABLE cms_topic
  ADD COLUMN finish_medal_name VARCHAR(40) NULL COMMENT 'P0.5 通关勋章名(空=默认 主题名·通关)',
  ADD COLUMN finish_medal_img VARCHAR(255) NULL COMMENT 'P0.5 通关勋章图';
