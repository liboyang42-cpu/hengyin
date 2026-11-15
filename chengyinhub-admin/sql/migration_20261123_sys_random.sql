-- ============================================================================
-- P3 随机系统：统一可审计随机服务 + M3 盲盒任务池 + 掉落规则管理菜单
-- ----------------------------------------------------------------------------
-- 仅新增表与幂等菜单/配置行；不动账本表（no ledger changes）、不删改存量表。
-- 回滚脚本：bak_sys_random_20261123_rollback.sql
-- ============================================================================

CREATE TABLE IF NOT EXISTS game_random_audit (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  domain       VARCHAR(48)  NOT NULL COMMENT '随机域: REWARD_DROP / PLAYKIT_COIN / PLAYKIT_DICE / PLAYKIT_D20 / PLAYKIT_RANDOM / JOURNEY_CHECK / CITY_DICE / TASK_DISPATCH',
  receipt_id   VARCHAR(64)  NOT NULL COMMENT '幂等/审计凭据ID',
  seed_digest  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '种子摘要或SECURE_RANDOM标记',
  ticket       INT          NOT NULL COMMENT '落点票号[0, total)',
  total        INT          NOT NULL COMMENT '总样本空间/总权重(>0)',
  member_id    BIGINT       NULL COMMENT '触发会员ID',
  ref_type     VARCHAR(48)  NULL COMMENT '关联对象类型',
  ref_id       VARCHAR(64)  NULL COMMENT '关联对象ID',
  outcome_json VARCHAR(500) NULL COMMENT '结果摘要JSON',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_gra_domain_receipt (domain, receipt_id),
  KEY idx_gra_member_time (member_id, created_at),
  KEY idx_gra_domain_time (domain, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一可审计随机流水';

CREATE TABLE IF NOT EXISTS node_task_pool (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  pool_name          VARCHAR(100) NOT NULL DEFAULT '' COMMENT '盲盒任务池名称',
  merchant_member_id BIGINT       NULL COMMENT '归属商家会员ID',
  entry_node_id      BIGINT       NULL COMMENT '入口门店节点ID(扫码触发此池)',
  topic_id           BIGINT       NULL COMMENT '关联主题ID',
  status             TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  create_by          VARCHAR(64)  NULL,
  create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time        DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ntp_merchant (merchant_member_id, status),
  KEY idx_ntp_entry_node (entry_node_id, status),
  KEY idx_ntp_topic (topic_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M3 盲盒任务池';

CREATE TABLE IF NOT EXISTS node_task_pool_item (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  pool_id     BIGINT   NOT NULL COMMENT '所属任务池ID',
  node_id     BIGINT   NOT NULL COMMENT '池中可选任务节点ID',
  weight      INT      NOT NULL DEFAULT 100 COMMENT '抽取权重(>=1)',
  status      TINYINT  NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ntpi_pool_node (pool_id, node_id),
  KEY idx_ntpi_pool_status (pool_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M3 盲盒任务池条目';

CREATE TABLE IF NOT EXISTS node_task_dispatch (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  member_id         BIGINT      NOT NULL COMMENT '会员ID',
  pool_id           BIGINT      NOT NULL COMMENT '任务池ID',
  dispatch_date     DATE        NOT NULL COMMENT '下发自然日(Asia/Shanghai)',
  node_id           BIGINT      NOT NULL COMMENT '当日抽中的任务节点ID',
  random_receipt_id VARCHAR(64) NULL COMMENT '对应game_random_audit.receipt_id',
  created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ntd_member_pool_date (member_id, pool_id, dispatch_date),
  KEY idx_ntd_member_node_date (member_id, node_id, dispatch_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M3 盲盒每日按人去重下发记录';

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '随机掉落-基础概率(万分)', 'reward.drop.base.prob.bp', '1500', 'N', 'admin', NOW(), 'M6 掉落基础概率,万分数(1500=15%)'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'reward.drop.base.prob.bp');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '随机掉落配置', (SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type = 'M' AND path = 'supply' LIMIT 1) m),
       58, 'reward-drop', 'business/rewarddrop/index', 1, 0, 'C', '0', '0', '', 'star', 'migration', NOW()
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_type = 'M' AND path = 'supply')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'business/rewarddrop/index');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '盲盒任务池', (SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type = 'M' AND path = 'supply' LIMIT 1) m),
       59, 'node-task-pool', 'business/nodetaskpool/index', 1, 0, 'C', '0', '0', '', 'component', 'migration', NOW()
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_type = 'M' AND path = 'supply')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'business/nodetaskpool/index');
