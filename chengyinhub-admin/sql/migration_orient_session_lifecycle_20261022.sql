-- Doc2 F1a(2026-10-22):城市定向(product_type=1)会话生命周期。不新建会话表,复用 topic_route_session。
-- 前置:migration_20260823_topic_route_graph.sql(topic_route_session / topic_route_action)。
-- 1) topic_route_session 加 phase 列(PREPARED/RUNNING/FINISHED/ABANDONED/DISQUALIFIED),可空:
--    存量行保持 NULL,读侧按 status + 是否有计时事实推导(进行中:有 orient_session_clock 行 = RUNNING,否则 PREPARED)。
--    不回填数据。
-- 2) status 注释补齐 2/3:2 = 已结束(含放弃,phase 区分),3 = 取消资格。只改注释,不改类型/默认值。
-- 3) 新建 orient_session_clock:每局至多一行(session_id 主键)的服务端计时事实。只有服务端时钟写入,不采信设备时间。
-- 新行为由 sys_config 'feature.flag.orientSession' 控制,缺省关闭;本文件不写入该配置。
-- 幂等:列 / 注释 / 表已就位则跳过。按 chengyin-db-migrate 流程只读预检 + 用户确认后执行,必须先于新 jar。
-- 回滚(确认无新 jar 依赖后):DROP TABLE orient_session_clock; ALTER TABLE topic_route_session DROP COLUMN phase;
SET @orient_phase_column = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'topic_route_session' AND column_name = 'phase');
SET @orient_phase_ddl = IF(@orient_phase_column = 0,
  'ALTER TABLE topic_route_session ADD COLUMN phase VARCHAR(16) NULL DEFAULT NULL COMMENT ''定向阶段 PREPARED/RUNNING/FINISHED/ABANDONED/DISQUALIFIED;NULL=存量,按 status+计时事实推导'' AFTER status',
  'SELECT 1');
PREPARE orient_phase_statement FROM @orient_phase_ddl;
EXECUTE orient_phase_statement;
DEALLOCATE PREPARE orient_phase_statement;

SET @orient_status_comment = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'topic_route_session' AND column_name = 'status'
    AND column_comment = '0进行中 1已到终点 2已结束(含放弃) 3取消资格');
SET @orient_status_ddl = IF(@orient_status_comment = 0,
  'ALTER TABLE topic_route_session MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT ''0进行中 1已到终点 2已结束(含放弃) 3取消资格''',
  'SELECT 1');
PREPARE orient_status_statement FROM @orient_status_ddl;
EXECUTE orient_status_statement;
DEALLOCATE PREPARE orient_status_statement;

CREATE TABLE IF NOT EXISTS orient_session_clock (
  session_id BIGINT NOT NULL COMMENT 'topic_route_session.id;一局至多一条计时事实',
  member_id BIGINT NOT NULL,
  started_at DATETIME(3) NOT NULL COMMENT '服务端开赛时间',
  ended_at DATETIME(3) DEFAULT NULL COMMENT '服务端终局时间;终局时一次写入',
  elapsed_ms BIGINT DEFAULT NULL COMMENT '终局冻结用时 = ended_at - started_at',
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (session_id),
  KEY idx_orient_clock_member (member_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市定向服务端计时事实';

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'topic_route_session' AND column_name = 'phase' AND is_nullable = 'YES') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'topic_route_session' AND column_name = 'status'
    AND column_comment = '0进行中 1已到终点 2已结束(含放弃) 3取消资格') = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'orient_session_clock' AND index_name = 'PRIMARY' AND column_name = 'session_id') = 1
);
