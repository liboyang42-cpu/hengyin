-- 刚到这座城·新生活装机计划 P2：标签、偏好题与结果幂等合同。
-- ③ 生产执行必须走 chengyin-db-migrate；本文件只准备，不在代码任务中直连生产库。
-- 前置：migration_m9_play_attempt_log.sql 与 migration_question_media_20260816.sql。

CREATE TABLE IF NOT EXISTS player_topic_tag (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  topic_id BIGINT NOT NULL,
  tag_code VARCHAR(32) NOT NULL COMMENT '服务端白名单枚举',
  tag_value VARCHAR(255) NOT NULL COMMENT '档位值JSON或枚举;不存原始答案',
  source_node_id BIGINT NOT NULL COMMENT '最近一次产生标签的节点',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0待确认 1已确认 2已撤回',
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_topic_tag (member_id, topic_id, tag_code),
  KEY idx_member_topic_status (member_id, topic_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家×主题可继承标签';

SET @preference_json_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='cms_member_template' AND column_name='preference_json');
SET @preference_json_sql := IF(@preference_json_exists=0,
    'ALTER TABLE cms_member_template ADD COLUMN preference_json JSON NULL COMMENT ''validationMethod=6 偏好题组配置'' AFTER question_option_media_json',
    'SELECT 1');
PREPARE preference_json_stmt FROM @preference_json_sql;
EXECUTE preference_json_stmt;
DEALLOCATE PREPARE preference_json_stmt;

SET @attempt_result_json_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='play_attempt_log' AND column_name='result_json');
SET @attempt_result_json_sql := IF(@attempt_result_json_exists=0,
    'ALTER TABLE play_attempt_log ADD COLUMN result_json JSON NULL COMMENT ''偏好题选择与确定性结果'' AFTER duration_s',
    'SELECT 1');
PREPARE attempt_result_json_stmt FROM @attempt_result_json_sql;
EXECUTE attempt_result_json_stmt;
DEALLOCATE PREPARE attempt_result_json_stmt;

SET @attempt_idem_exists := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='play_attempt_log' AND column_name='idempotency_key');
SET @attempt_idem_sql := IF(@attempt_idem_exists=0,
    'ALTER TABLE play_attempt_log ADD COLUMN idempotency_key VARCHAR(128) NULL COMMENT ''偏好题提交幂等键;普通尝试为空'' AFTER result_json',
    'SELECT 1');
PREPARE attempt_idem_stmt FROM @attempt_idem_sql;
EXECUTE attempt_idem_stmt;
DEALLOCATE PREPARE attempt_idem_stmt;

SET @attempt_idem_index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='play_attempt_log' AND index_name='uk_play_attempt_idempotency');
SET @attempt_idem_index_sql := IF(@attempt_idem_index_exists=0,
    'ALTER TABLE play_attempt_log ADD UNIQUE INDEX uk_play_attempt_idempotency (idempotency_key)',
    'SELECT 1');
PREPARE attempt_idem_index_stmt FROM @attempt_idem_index_sql;
EXECUTE attempt_idem_index_stmt;
DEALLOCATE PREPARE attempt_idem_index_stmt;

-- 回读合同：标签表、三列及幂等唯一索引必须由数据库自身回报。
-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema=DATABASE() AND table_name='player_topic_tag' AND engine='InnoDB') = 1
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='player_topic_tag'
        AND (
          (column_name='id' AND ordinal_position=1 AND column_type='bigint' AND is_nullable='NO'
            AND column_default IS NULL AND extra='auto_increment') OR
          (column_name='member_id' AND ordinal_position=2 AND column_type='bigint' AND is_nullable='NO'
            AND column_default IS NULL AND extra='') OR
          (column_name='topic_id' AND ordinal_position=3 AND column_type='bigint' AND is_nullable='NO'
            AND column_default IS NULL AND extra='') OR
          (column_name='tag_code' AND ordinal_position=4 AND column_type='varchar(32)' AND is_nullable='NO'
            AND column_default IS NULL AND extra='' AND character_set_name='utf8mb4') OR
          (column_name='tag_value' AND ordinal_position=5 AND column_type='varchar(255)' AND is_nullable='NO'
            AND column_default IS NULL AND extra='' AND character_set_name='utf8mb4') OR
          (column_name='source_node_id' AND ordinal_position=6 AND column_type='bigint' AND is_nullable='NO'
            AND column_default IS NULL AND extra='') OR
          (column_name='status' AND ordinal_position=7 AND column_type='tinyint' AND is_nullable='NO'
            AND column_default='0' AND extra='') OR
          (column_name='create_time' AND ordinal_position=8 AND column_type='datetime' AND is_nullable='NO'
            AND column_default IS NULL AND extra='') OR
          (column_name='update_time' AND ordinal_position=9 AND column_type='datetime' AND is_nullable='NO'
            AND column_default IS NULL AND extra='')
        )) = 9
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='player_topic_tag') = 9
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND (
        (table_name='cms_member_template' AND column_name='preference_json'
          AND column_type='json' AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
        (table_name='play_attempt_log' AND column_name='result_json'
          AND column_type='json' AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
        (table_name='play_attempt_log' AND column_name='idempotency_key'
          AND column_type='varchar(128)' AND is_nullable='YES' AND column_default IS NULL AND extra=''))) = 3
    AND
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='player_topic_tag'
        AND index_name='PRIMARY') = 1
    AND
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='player_topic_tag'
        AND index_name='PRIMARY' AND non_unique=0 AND index_type='BTREE'
        AND sub_part IS NULL AND is_visible='YES'
        AND seq_in_index=1 AND column_name='id') = 1
    AND
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='play_attempt_log'
        AND index_name='uk_play_attempt_idempotency') = 1
    AND
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='play_attempt_log'
        AND index_name='uk_play_attempt_idempotency' AND non_unique=0 AND index_type='BTREE'
        AND sub_part IS NULL AND is_visible='YES'
        AND seq_in_index=1 AND column_name='idempotency_key') = 1
    AND
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='player_topic_tag'
        AND index_name='uk_member_topic_tag') = 3
    AND
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='player_topic_tag'
        AND index_name='uk_member_topic_tag' AND non_unique=0 AND index_type='BTREE'
        AND sub_part IS NULL AND is_visible='YES'
        AND ((seq_in_index=1 AND column_name='member_id')
          OR (seq_in_index=2 AND column_name='topic_id')
          OR (seq_in_index=3 AND column_name='tag_code'))) = 3
) AS migration_assertion;

-- 唯一键负控（由 chengyin-db-migrate 在迁移窗口人工执行，本次不直连生产）：
-- 1) 先写入基准行；2) 重复写入必须收到 MySQL 1062；3) 立即执行清理语句。
-- INSERT INTO player_topic_tag
--   (member_id,topic_id,tag_code,tag_value,source_node_id,status,create_time,update_time)
-- VALUES (-20260820,-20260820,'__migration_negative__','first',-20260820,0,NOW(),NOW());
-- INSERT INTO player_topic_tag
--   (member_id,topic_id,tag_code,tag_value,source_node_id,status,create_time,update_time)
-- VALUES (-20260820,-20260820,'__migration_negative__','duplicate',-20260820,0,NOW(),NOW());
-- -- 上一句必须 ERROR 1062 Duplicate entry ... for key 'uk_member_topic_tag'。
-- DELETE FROM player_topic_tag
--  WHERE member_id=-20260820 AND topic_id=-20260820 AND tag_code='__migration_negative__';
--
-- INSERT INTO play_attempt_log
--   (member_id,topic_id,node_id,activity_id,attempt_type,result,created_at,idempotency_key)
-- VALUES (-20260820,-20260820,-20260820,-20260820,6,1,NOW(),'__migration_negative_20260820__');
-- INSERT INTO play_attempt_log
--   (member_id,topic_id,node_id,activity_id,attempt_type,result,created_at,idempotency_key)
-- VALUES (-20260820,-20260820,-20260820,-20260820,6,1,NOW(),'__migration_negative_20260820__');
-- -- 上一句必须 ERROR 1062 Duplicate entry ... for key 'uk_play_attempt_idempotency'。
-- DELETE FROM play_attempt_log WHERE idempotency_key='__migration_negative_20260820__';

-- 回滚（有业务数据后须先导出，禁止盲删）：
-- ALTER TABLE play_attempt_log DROP INDEX uk_play_attempt_idempotency;
-- ALTER TABLE play_attempt_log DROP COLUMN idempotency_key, DROP COLUMN result_json;
-- ALTER TABLE cms_member_template DROP COLUMN preference_json;
-- DROP TABLE player_topic_tag;
