-- 主题级地点分支路线与玩家权威路线局。
-- 生产执行必须走 chengyin-db-migrate；本文件只随代码交付，不自动落库。

SET @route_mode_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='route_mode');
SET @route_mode_sql := IF(@route_mode_exists=0,
  'ALTER TABLE cms_topic ADD COLUMN route_mode VARCHAR(20) NOT NULL DEFAULT ''LINEAR'' COMMENT ''LINEAR/BRANCH_GRAPH'' AFTER complete_rule_json',
  'SELECT 1');
PREPARE route_mode_stmt FROM @route_mode_sql; EXECUTE route_mode_stmt; DEALLOCATE PREPARE route_mode_stmt;

SET @route_graph_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='route_graph_json');
SET @route_graph_sql := IF(@route_graph_exists=0,
  'ALTER TABLE cms_topic ADD COLUMN route_graph_json JSON NULL COMMENT ''主题级地点路线图'' AFTER route_mode',
  'SELECT 1');
PREPARE route_graph_stmt FROM @route_graph_sql; EXECUTE route_graph_stmt; DEALLOCATE PREPARE route_graph_stmt;

SET @route_version_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='config_version');
SET @route_version_sql := IF(@route_version_exists=0,
  'ALTER TABLE cms_topic ADD COLUMN config_version BIGINT NOT NULL DEFAULT 1 COMMENT ''路线配置版本'' AFTER route_graph_json',
  'SELECT 1');
PREPARE route_version_stmt FROM @route_version_sql; EXECUTE route_version_stmt; DEALLOCATE PREPARE route_version_stmt;

CREATE TABLE IF NOT EXISTS topic_route_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '活动场次；自玩为0',
  topic_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default',
  config_version BIGINT NOT NULL,
  config_snapshot_json JSON NOT NULL,
  state_json JSON NOT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0进行中 1已到终点',
  current_node_id BIGINT DEFAULT NULL,
  recommended_node_id BIGINT DEFAULT NULL,
  seed BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  completed_at DATETIME(3) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_topic_route_owner (activity_id,topic_id,member_id,play_instance_id),
  KEY idx_topic_route_current (topic_id,current_node_id,status),
  KEY idx_topic_route_member (member_id,status,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家主题地点路线权威会话';

CREATE TABLE IF NOT EXISTS topic_route_action (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  node_id BIGINT NOT NULL,
  action_id VARCHAR(96) NOT NULL COMMENT '客户端幂等 actionId；只在本局/本玩家内判重',
  receipt_id VARCHAR(96) NOT NULL COMMENT '服务端节点完成事实与expectedVersion的SHA-256绑定值',
  outcome_code VARCHAR(64) NOT NULL,
  response_json JSON NOT NULL COMMENT '首次推进返回的 routeState 快照',
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_topic_route_action (session_id,member_id,action_id),
  UNIQUE KEY uk_topic_route_receipt (session_id,receipt_id) COMMENT '仅同一权威访问轮次内判重；合法loop可再次完成同节点',
  KEY idx_topic_route_action_session (session_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主题路线推进幂等回执';

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='cms_topic' AND column_name IN ('route_mode','route_graph_json','config_version'))=3
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='topic_route_session' AND index_name='uk_topic_route_owner' AND non_unique=0)=4
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='topic_route_action' AND index_name='uk_topic_route_action' AND non_unique=0)=3
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='topic_route_action' AND index_name='uk_topic_route_receipt' AND non_unique=0)=2
) AS migration_assertion;

-- 回滚（有运行数据后必须先导出，禁止盲删）：
-- DROP TABLE topic_route_action;
-- DROP TABLE topic_route_session;
-- ALTER TABLE cms_topic DROP COLUMN config_version, DROP COLUMN route_graph_json, DROP COLUMN route_mode;
