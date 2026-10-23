-- ================= migration_orient_scoring_20261022.sql =================
SELECT 'migration_orient_scoring_20261022.sql' AS running;
-- 实施文档二 F1b 城市定向计分(product_type=1):
--   orient_score_ledger      只追加计分流水,分值单位 _centi(1/100 分,有符号整数增量);
--                            纠错 = 追加一条反向条目(reverses_entry_id),从不 UPDATE/DELETE。
--   orient_score_settlement  局终一次性投影到积分/XP;uk(session_id) 保证同一局奖励恰好一次;
--                            ledger_watermark_id 记录投影时的流水水位,可复算:
--                            SUM(delta_centi WHERE id <= watermark) == total_centi。
--   orient_score_appeal      计分申诉(针对某条流水或某个任务)。
--   orient_result_dispute    成绩争议(局终后 dispute window 内可提)。
--   sys_config 落种:orient.scoring.* 默认全部关闭/不发奖,落种后线上行为不变。
-- 依赖:topic_route_session(migration_20260823_topic_route_graph)、sys_config(RuoYi 基础表)。
-- 全部 CREATE TABLE IF NOT EXISTS + INSERT ... WHERE NOT EXISTS,可重复执行。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
--
-- 落库前只读预检:
--   SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()
--     AND table_name IN ('orient_score_ledger','orient_score_settlement','orient_score_appeal','orient_result_dispute');
--   SELECT config_key, config_value FROM sys_config WHERE config_key LIKE 'orient.scoring.%';

CREATE TABLE IF NOT EXISTS orient_score_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL COMMENT 'topic_route_session.id',
  activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '活动场次;自玩为0',
  topic_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  dimension VARCHAR(32) NOT NULL COMMENT '计分维度:TASK任务 PUZZLE谜题 PENALTY处罚 BONUS奖励 ADJUSTMENT申诉/人工修正',
  delta_centi INT NOT NULL COMMENT '有符号增量,单位1/100分',
  reason_code VARCHAR(64) NOT NULL,
  source_type VARCHAR(32) NOT NULL COMMENT 'PROGRESS/ANTI_CHEAT/APPEAL/DISPUTE/ADMIN/LIFECYCLE',
  source_ref VARCHAR(96) DEFAULT NULL COMMENT '来源对象,如 node:12 / appeal:3',
  node_id BIGINT DEFAULT NULL,
  reverses_entry_id BIGINT DEFAULT NULL COMMENT '反向冲正的原条目;每条至多被冲正一次',
  idempotency_key VARCHAR(96) NOT NULL COMMENT '本局内幂等键',
  request_fingerprint CHAR(64) NOT NULL COMMENT '请求内容SHA-256;同键不同指纹=冲突',
  operator_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM/ADMIN',
  operator_id BIGINT DEFAULT NULL,
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_orient_ledger_idem (session_id, idempotency_key),
  UNIQUE KEY uk_orient_ledger_reverse (reverses_entry_id),
  KEY idx_orient_ledger_session (session_id, id),
  KEY idx_orient_ledger_board (topic_id, activity_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市定向计分流水(只追加;纠错为反向条目)';

CREATE TABLE IF NOT EXISTS orient_score_settlement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  activity_id BIGINT NOT NULL DEFAULT 0,
  topic_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  total_centi BIGINT NOT NULL COMMENT '投影时 SUM(delta_centi WHERE id<=ledger_watermark_id)',
  entry_count INT NOT NULL,
  ledger_watermark_id BIGINT NOT NULL DEFAULT 0 COMMENT '投影时的流水最大 id;0=无流水',
  points_awarded INT NOT NULL DEFAULT 0,
  xp_awarded INT NOT NULL DEFAULT 0,
  points_rate_pct INT NOT NULL DEFAULT 0 COMMENT '规则快照:每1分折算积分的百分比',
  xp_rate_pct INT NOT NULL DEFAULT 0 COMMENT '规则快照:每1分折算XP的百分比',
  idempotency_key VARCHAR(96) NOT NULL COMMENT '首次结算请求键(回执回读用)',
  request_fingerprint CHAR(64) NOT NULL COMMENT '投影内容SHA-256',
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_orient_settlement_session (session_id),
  KEY idx_orient_settlement_member (member_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市定向局终积分/XP投影(每局恰好一次)';

CREATE TABLE IF NOT EXISTS orient_score_appeal (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  entry_id BIGINT DEFAULT NULL COMMENT '被申诉的流水条目;与 node_id 至少一个非空',
  node_id BIGINT DEFAULT NULL COMMENT '被申诉的任务节点(如漏记)',
  reason VARCHAR(500) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1采纳 2驳回',
  resolution_entry_id BIGINT DEFAULT NULL COMMENT '采纳时追加的修正流水',
  resolution_note VARCHAR(500) DEFAULT NULL,
  handled_by VARCHAR(64) DEFAULT NULL,
  handled_at DATETIME(3) DEFAULT NULL,
  idempotency_key VARCHAR(96) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_orient_appeal_idem (member_id, idempotency_key),
  KEY idx_orient_appeal_session (session_id, status),
  KEY idx_orient_appeal_status (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市定向计分申诉';

CREATE TABLE IF NOT EXISTS orient_result_dispute (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1成立 2不成立',
  window_deadline DATETIME(3) NOT NULL COMMENT '争议窗口截止(局终时间+窗口时长)',
  resolution_note VARCHAR(500) DEFAULT NULL,
  handled_by VARCHAR(64) DEFAULT NULL,
  handled_at DATETIME(3) DEFAULT NULL,
  idempotency_key VARCHAR(96) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_orient_dispute_idem (member_id, idempotency_key),
  KEY idx_orient_dispute_session (session_id, status),
  KEY idx_orient_dispute_status (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市定向成绩争议(带争议窗口)';

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市定向计分-总开关', 'orient.scoring.enabled', 'false', 'N', 'admin', NOW(),
       'true=对 product_type=1 主题启用计分流水/局终投影/申诉争议;false(默认)=全部关闭,旧行为不变'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'orient.scoring.enabled');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市定向计分-每任务分值', 'orient.scoring.task_centi', '10000', 'N', 'admin', NOW(),
       '每个已通过(非待审)任务计入的分值,单位1/100分;10000=100分'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'orient.scoring.task_centi');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市定向计分-积分折算', 'orient.scoring.points_rate_pct', '0', 'N', 'admin', NOW(),
       '局终投影:每1分折算积分的百分比;0(默认)=不发积分,待 owner 定值'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'orient.scoring.points_rate_pct');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市定向计分-XP折算', 'orient.scoring.xp_rate_pct', '0', 'N', 'admin', NOW(),
       '局终投影:每1分折算XP的百分比;0(默认)=不发XP,待 owner 定值'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'orient.scoring.xp_rate_pct');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市定向计分-争议窗口(小时)', 'orient.scoring.dispute_window_hours', '24', 'N', 'admin', NOW(),
       '局终后可提交计分申诉/成绩争议的时长(小时)'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'orient.scoring.dispute_window_hours');

-- 回读断言:四张表都在,五个配置键各恰好一行(不校验值 —— 运维事先配置过的值保持原样)。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE()
           AND table_name IN ('orient_score_ledger','orient_score_settlement',
                              'orient_score_appeal','orient_result_dispute')) = 4
   AND (SELECT SUM(config_key = 'orient.scoring.enabled') = 1
           AND SUM(config_key = 'orient.scoring.task_centi') = 1
           AND SUM(config_key = 'orient.scoring.points_rate_pct') = 1
           AND SUM(config_key = 'orient.scoring.xp_rate_pct') = 1
           AND SUM(config_key = 'orient.scoring.dispute_window_hours') = 1
          FROM sys_config WHERE config_key LIKE 'orient.scoring.%') AS assertion_ok;
