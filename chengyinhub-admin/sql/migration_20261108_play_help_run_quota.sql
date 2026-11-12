-- BE-16 整局共享免费提示额度（G7）。
-- 只新增计数器表；不改 play_attempt_log / ums_member_points_detail / topic_route_session。
-- 生产执行必须走 chengyin-db-migrate；本文件只随代码交付，不自动落库。

-- 预检：同名表若已存在但形状不对，下面的断言必须失败，绝不静默沿用坏表。
SET @quota_table_exists := (
    SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name = 'play_help_run_quota');

SET @quota_shape_ok := (
    @quota_table_exists = 0
    OR (
        SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'play_help_run_quota'
           AND (
             (column_name = 'id' AND column_type = 'bigint' AND is_nullable = 'NO' AND extra = 'auto_increment') OR
             (column_name = 'topic_id' AND column_type = 'bigint' AND is_nullable = 'NO') OR
             (column_name = 'activity_id' AND column_type = 'bigint' AND is_nullable = 'NO' AND column_default = '0') OR
             (column_name = 'play_instance_id' AND column_type = 'varchar(64)' AND is_nullable = 'NO') OR
             (column_name = 'owner_member_id' AND column_type = 'bigint' AND is_nullable = 'NO') OR
             (column_name = 'quota_total' AND column_type = 'int' AND is_nullable = 'NO') OR
             (column_name = 'quota_used' AND column_type = 'int' AND is_nullable = 'NO' AND column_default = '0') OR
             (column_name = 'version' AND column_type = 'int' AND is_nullable = 'NO' AND column_default = '0') OR
             (column_name = 'create_time' AND column_type = 'datetime(3)' AND is_nullable = 'NO') OR
             (column_name = 'update_time' AND column_type = 'datetime(3)' AND is_nullable = 'NO')
           )
    ) = 10
);
-- runner: assert=scalar-true
SELECT @quota_shape_ok AS play_help_run_quota_precheck;
SET @quota_guard_sql := IF(@quota_shape_ok,
    'SELECT 1', 'SELECT * FROM information_schema.__play_help_run_quota_precheck_failed__');
PREPARE quota_guard FROM @quota_guard_sql;
EXECUTE quota_guard;
DEALLOCATE PREPARE quota_guard;

CREATE TABLE IF NOT EXISTS play_help_run_quota (
  id BIGINT NOT NULL AUTO_INCREMENT,
  topic_id BIGINT NOT NULL COMMENT '主题ID',
  activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '活动场次；自玩为0',
  play_instance_id VARCHAR(64) NOT NULL COMMENT '本局真实游玩实例；来自 topic_route_session.play_instance_id',
  owner_member_id BIGINT NOT NULL COMMENT '本局 owner；额度 key 必含，防同 playInstance 不同会员撞池',
  quota_total INT NOT NULL COMMENT '本局免费N次；首次使用时冻结，之后不随主题改价重置',
  quota_used INT NOT NULL DEFAULT 0 COMMENT '已消费免费次数',
  version INT NOT NULL DEFAULT 0 COMMENT 'CAS/审计版本',
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_help_run_quota_scope (topic_id, activity_id, play_instance_id, owner_member_id),
  KEY idx_help_run_quota_owner (owner_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BE-16 整局共享免费提示额度';

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'play_help_run_quota' AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'play_help_run_quota') = 10
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'play_help_run_quota'
      AND index_name = 'uk_help_run_quota_scope' AND non_unique = 0
      AND index_type = 'BTREE' AND sub_part IS NULL) = 4
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'play_help_run_quota'
      AND index_name = 'uk_help_run_quota_scope' AND non_unique = 0
      AND ((seq_in_index = 1 AND column_name = 'topic_id')
        OR (seq_in_index = 2 AND column_name = 'activity_id')
        OR (seq_in_index = 3 AND column_name = 'play_instance_id')
        OR (seq_in_index = 4 AND column_name = 'owner_member_id'))) = 4
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'play_help_run_quota'
      AND index_name = 'PRIMARY' AND non_unique = 0 AND seq_in_index = 1
      AND column_name = 'id') = 1
) AS play_help_run_quota_ready;

-- 回退限制：已有运行数据后禁止盲删；需要回退时先导出并按运营流程处理。
-- DROP TABLE play_help_run_quota;
