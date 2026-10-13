-- 高级玩法配置与服务端权威运行会话。
-- 生产执行必须走 chengyin-db-migrate；本文件只随代码交付，不自动落库。

SET @member_advanced_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE()
       AND table_name='cms_member_template'
       AND column_name='advanced_config_json');
SET @member_advanced_sql := IF(@member_advanced_exists=0,
    'ALTER TABLE cms_member_template ADD COLUMN advanced_config_json JSON NULL COMMENT ''高级玩法配置(schemaVersion=1)'' AFTER preference_json',
    'SELECT 1');
PREPARE member_advanced_stmt FROM @member_advanced_sql;
EXECUTE member_advanced_stmt;
DEALLOCATE PREPARE member_advanced_stmt;

SET @library_advanced_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE()
       AND table_name='cms_template_library'
       AND column_name='advanced_config_json');
SET @library_advanced_sql := IF(@library_advanced_exists=0,
    'ALTER TABLE cms_template_library ADD COLUMN advanced_config_json JSON NULL COMMENT ''高级玩法配置(schemaVersion=1)'' AFTER preference_json',
    'SELECT 1');
PREPARE library_advanced_stmt FROM @library_advanced_sql;
EXECUTE library_advanced_stmt;
DEALLOCATE PREPARE library_advanced_stmt;

CREATE TABLE IF NOT EXISTS game_runtime_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '活动场次;自玩为0',
  topic_id BIGINT NOT NULL,
  node_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  owner_type TINYINT NOT NULL COMMENT '1玩家 2队伍',
  owner_id BIGINT NOT NULL COMMENT 'member_id或play_team.id',
  config_snapshot_json JSON NOT NULL COMMENT '开始时冻结的模板高级玩法配置',
  state_json JSON NOT NULL COMMENT '随机结果/分支/角色/轮次等权威状态',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0进行中 1完成 2失败 3超时',
  score INT NOT NULL DEFAULT 0,
  completed_units INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0 COMMENT 'CAS版本',
  started_at DATETIME(3) NOT NULL,
  deadline_at DATETIME(3) DEFAULT NULL,
  completed_at DATETIME(3) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_runtime_owner (activity_id, topic_id, node_id, owner_type, owner_id),
  KEY idx_runtime_leaderboard (activity_id, topic_id, node_id, status, score, completed_units, completed_at),
  KEY idx_runtime_deadline (status, deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='高级玩法权威运行会话';

-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='cms_member_template'
        AND column_name='advanced_config_json' AND data_type='json' AND is_nullable='YES') = 1
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='cms_template_library'
        AND column_name='advanced_config_json' AND data_type='json' AND is_nullable='YES') = 1
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_runtime_session'
        AND column_name IN ('config_snapshot_json','state_json') AND data_type='json') = 2
    AND
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='game_runtime_session'
        AND index_name='uk_runtime_owner' AND non_unique=0) = 5
) AS migration_assertion;

-- 回滚（有运行数据后须先导出，禁止盲删）：
-- DROP TABLE game_runtime_session;
-- ALTER TABLE cms_template_library DROP COLUMN advanced_config_json;
-- ALTER TABLE cms_member_template DROP COLUMN advanced_config_json;
