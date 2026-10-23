-- Doc2 F1c / O-02(2026-10-22):member_visited_poi 加稳定身份列 poi_key,不再以 3 位小数坐标(geo_key)为身份。
-- 前置:migration_member_visited_poi.sql(建表)。只加可空列 + 唯一键,不改不删既有列/索引;
-- 旧 jar 与开关关闭的新 jar 都不读写 poi_key,落库后行为不变。
-- 取值:poi:{roam_poi.id}(50m 内上架地标)或 pt:{5 位小数}(首访锚点标签);旧行由
-- migration_member_visited_poi_poi_key_backfill_20261022.sql 回填,重复行保持 null。
-- 顺序:本迁移 → 回填迁移 → 才能打开 sys_config['roam.visited_poi.stable_identity']。
-- 幂等:列/索引已存在则跳过。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
-- 回滚(确认开关为 false 后):ALTER TABLE member_visited_poi DROP INDEX uk_member_poi_key, DROP COLUMN poi_key;
SET @member_visited_poi_key_column = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND column_name = 'poi_key');
SET @member_visited_poi_key_ddl = IF(@member_visited_poi_key_column = 0,
  'ALTER TABLE member_visited_poi ADD COLUMN poi_key VARCHAR(32) NULL COMMENT ''O-02 稳定身份:poi:{roam_poi.id} 或 pt:{首访锚点};旧行重复项为 null'' AFTER geo_key',
  'SELECT 1');
PREPARE member_visited_poi_key_statement FROM @member_visited_poi_key_ddl;
EXECUTE member_visited_poi_key_statement;
DEALLOCATE PREPARE member_visited_poi_key_statement;

SET @member_visited_poi_key_index = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND index_name = 'uk_member_poi_key');
SET @member_visited_poi_key_index_ddl = IF(@member_visited_poi_key_index = 0,
  'ALTER TABLE member_visited_poi ADD UNIQUE KEY uk_member_poi_key (member_id, poi_key)', 'SELECT 1');
PREPARE member_visited_poi_key_index_statement FROM @member_visited_poi_key_index_ddl;
EXECUTE member_visited_poi_key_index_statement;
DEALLOCATE PREPARE member_visited_poi_key_index_statement;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
          AND table_name = 'member_visited_poi' AND column_name = 'poi_key'
          AND column_type = 'varchar(32)' AND is_nullable = 'YES') = 1
   AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
          AND table_name = 'member_visited_poi' AND index_name = 'uk_member_poi_key' AND non_unique = 0) = 2
   AS assertion_ok;
