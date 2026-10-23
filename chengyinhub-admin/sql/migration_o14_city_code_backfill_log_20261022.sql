-- O-14:city_code 回填来源标记表(RV3 B-519-1)。只记 migration_o14_content_city_code_backfill_20261022.sql 自己写入的行。
-- 规则修正后重跑，回填据此只改写「本回填写过、且值至今未被改动」的行；人工填写/修正的值不在表内，永远不会被回填覆盖。
-- 纯新增表，不动既有表；旧/新 jar 都不读写它，可先于 jar 执行。
-- 幂等：CREATE TABLE IF NOT EXISTS；已存在同名表时由断言核对关键列，不替换、不删除。
CREATE TABLE IF NOT EXISTS o14_city_code_backfill_log (
  target_table VARCHAR(32) NOT NULL COMMENT 'cms_topic/cms_activity',
  row_id BIGINT NOT NULL COMMENT '目标表主键',
  city_code VARCHAR(32) NOT NULL COMMENT '本回填写入的值',
  source VARCHAR(64) NOT NULL COMMENT '规则版本与来源，如 o14_v2:club_city',
  write_time DATETIME NOT NULL COMMENT '写入时间',
  PRIMARY KEY (target_table, row_id),
  CONSTRAINT chk_o14_city_backfill_target CHECK (target_table IN ('cms_topic','cms_activity'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='O-14 city_code 回填来源标记';

-- runner: assert=scalar-true
SELECT COUNT(*)=5 AS assertion_ok
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='o14_city_code_backfill_log'
  AND column_name IN ('target_table','row_id','city_code','source','write_time');
