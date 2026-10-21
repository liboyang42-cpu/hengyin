-- 玩法说明的专用追加审计；不恢复通用请求/响应正文留存，不使用会员身份。
CREATE TABLE IF NOT EXISTS cms_infomation_change_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  infomation_id BIGINT NOT NULL,
  action VARCHAR(6) NOT NULL,
  operator_user_id BIGINT NOT NULL,
  operator_username VARCHAR(64) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_infomation_audit_target (infomation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='玩法说明专用变更审计；仅追加，禁止保存完整请求/响应';

-- 同名残缺表不得被 IF NOT EXISTS 静默接受；重复运行只校验，不改历史记录。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema=DATABASE() AND table_name='cms_infomation_change_audit'
     AND engine='InnoDB' AND table_collation='utf8mb4_unicode_ci') = 1
  AND (SELECT COUNT(*) = 7 AND SUM(
    CASE
      WHEN ordinal_position=1 AND column_name='id' AND column_type='bigint'
        AND extra='auto_increment' THEN 1
      WHEN ordinal_position=2 AND column_name='infomation_id' AND column_type='bigint' AND extra='' THEN 1
      WHEN ordinal_position=3 AND column_name='action' AND column_type='varchar(6)'
        AND collation_name='utf8mb4_unicode_ci' AND extra='' THEN 1
      WHEN ordinal_position=4 AND column_name='operator_user_id' AND column_type='bigint' AND extra='' THEN 1
      WHEN ordinal_position=5 AND column_name='operator_username' AND column_type='varchar(64)'
        AND collation_name='utf8mb4_unicode_ci' AND extra='' THEN 1
      WHEN ordinal_position=6 AND column_name='reason' AND column_type='varchar(500)'
        AND collation_name='utf8mb4_unicode_ci' AND extra='' THEN 1
      WHEN ordinal_position=7 AND column_name='create_time' AND column_type='datetime' AND extra='' THEN 1
      ELSE 0
    END) = 7 AND SUM(is_nullable='NO' AND column_default IS NULL AND generation_expression='') = 7
    FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='cms_infomation_change_audit')
  AND (SELECT COUNT(*) = 3 AND SUM(
    (index_name='PRIMARY' AND non_unique=0 AND seq_in_index=1 AND column_name='id')
    OR (index_name='idx_infomation_audit_target' AND non_unique=1 AND seq_in_index=1 AND column_name='infomation_id')
    OR (index_name='idx_infomation_audit_target' AND non_unique=1 AND seq_in_index=2 AND column_name='id')) = 3
    AND SUM(index_type='BTREE' AND sub_part IS NULL AND collation='A' AND is_visible='YES') = 3
    FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='cms_infomation_change_audit')
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema=DATABASE() AND table_name='cms_infomation_change_audit') = 1
  AND (SELECT COUNT(*) FROM information_schema.triggers
       WHERE trigger_schema=DATABASE() AND event_object_table='cms_infomation_change_audit') = 0
  AS assertion_ok;
