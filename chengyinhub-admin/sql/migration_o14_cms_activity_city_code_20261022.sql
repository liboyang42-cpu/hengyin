-- O-14(DOC2 survey item 8 / C8-1 选 B):cms_activity 新增可空 city_code(城市 adcode,城市级,如 310100)。
-- 只新增列与索引，不删、不改、不回填既有数据；回填见 migration_o14_content_city_code_backfill_20261022.sql(另行确认后执行)。
-- 前置：cms_activity 在 sql/base_schema_20260818.sql。
-- 落库顺序：可先于新 jar 执行；旧 jar 不读写本列(SELECT 列都是显式列名)，新 jar 只在 O-14 任务/统一搜索开启后读它。
-- 幂等：ALTER 前查 information_schema；同名但形状不符的列 fail-closed，不替换、不删除。
DROP TEMPORARY TABLE IF EXISTS o14_cms_activity_city_code_guard;
CREATE TEMPORARY TABLE o14_cms_activity_city_code_guard(id TINYINT PRIMARY KEY);
INSERT INTO o14_cms_activity_city_code_guard VALUES(1);

-- 目标表必须存在且为 InnoDB；否则下一条 INSERT 撞主键，整个文件停在这里。
INSERT INTO o14_cms_activity_city_code_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name='cms_activity' AND engine='InnoDB') <> 1;

-- 已存在的同名列必须正是本迁移要的形状(VARCHAR(32) NULL)；不符就停，不当成已完成。
INSERT INTO o14_cms_activity_city_code_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='cms_activity' AND column_name='city_code'
    AND NOT (data_type='varchar' AND character_maximum_length=32 AND is_nullable='YES'));

SET @o14_activity_city_code_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='cms_activity' AND column_name='city_code'),
  'SELECT 1',
  'ALTER TABLE cms_activity ADD COLUMN city_code VARCHAR(32) NULL DEFAULT NULL COMMENT ''城市编码(adcode 城市级，可空)'', ALGORITHM=INSTANT'
);
PREPARE o14_activity_city_code_stmt FROM @o14_activity_city_code_ddl;
EXECUTE o14_activity_city_code_stmt;
DEALLOCATE PREPARE o14_activity_city_code_stmt;

SET @o14_activity_city_code_idx_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema=DATABASE() AND table_name='cms_activity' AND index_name='idx_cms_activity_city_code'),
  'SELECT 1',
  'ALTER TABLE cms_activity ADD INDEX idx_cms_activity_city_code (city_code), ALGORITHM=INPLACE, LOCK=NONE'
);
PREPARE o14_activity_city_code_idx_stmt FROM @o14_activity_city_code_idx_ddl;
EXECUTE o14_activity_city_code_idx_stmt;
DEALLOCATE PREPARE o14_activity_city_code_idx_stmt;

DROP TEMPORARY TABLE o14_cms_activity_city_code_guard;

-- runner: assert=scalar-true
SELECT COUNT(*)=1 AS assertion_ok
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='cms_activity' AND column_name='city_code'
  AND data_type='varchar' AND character_maximum_length=32 AND is_nullable='YES';

-- runner: assert=scalar-true
SELECT COUNT(*)>=1 AS assertion_ok
FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='cms_activity' AND index_name='idx_cms_activity_city_code';
