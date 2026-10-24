-- DEFER-ROAM(2026-10-24,C:U-102):preset_route_node 经纬度补数值列与地理索引。
-- 背景:longitude / latitude 是 varchar(32),按坐标范围筛点只能全表扫 + 逐行字符串转数。
-- 做法:加两个 STORED 生成列 lng_num / lat_num DECIMAL(10,7),由库自动从原文本列派生、自动回填、
--   随写入自动同步 —— 写入方(CmsPresetRouteNodeMapper)一行不改,原 varchar 列仍是唯一真源。
--   派生规则:TRIM 后是十进制数字(无指数)且落在经纬度合法范围内才取值,否则 NULL;超过 7 位小数四舍五入。
--   在 STRICT_TRANS_TABLES 下实测:非法文本('abc'、''、'1e2'、越界)只得 NULL,不会让原有写入报错。
-- 再加 idx_preset_node_geo(lat_num, lng_num) 供包围盒查询。本 PR 不改任何读取方。
-- 幂等:三个动作各自 information_schema 预检 + PREPARE,可重复执行。
-- ⚠️ 加 STORED 生成列走 COPY 重建表(该表为运营预设路线,行数很少);生产执行前走 chengyin-db-migrate
--    只读预检 + owner 确认,本 PR 不在任何库上执行。
-- ⚠️ 之后若要 MODIFY longitude/latitude 列,须先 DROP 这两个生成列(MySQL 不允许改被生成列引用的列)。
-- 回滚:ALTER TABLE preset_route_node DROP INDEX idx_preset_node_geo, DROP COLUMN lat_num, DROP COLUMN lng_num;
SET @preset_node_lng_num_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'preset_route_node' AND column_name = 'lng_num'
);
SET @preset_node_lng_num_sql := IF(
    @preset_node_lng_num_exists = 0,
    'ALTER TABLE preset_route_node ADD COLUMN lng_num DECIMAL(10,7) GENERATED ALWAYS AS (CASE WHEN TRIM(longitude) REGEXP ''^-?[0-9]{1,3}([.][0-9]+)?$'' AND CAST(TRIM(longitude) AS DECIMAL(20,12)) BETWEEN -180 AND 180 THEN CAST(TRIM(longitude) AS DECIMAL(10,7)) END) STORED COMMENT ''经度数值(由 longitude 派生,只读)''',
    'SELECT 1'
);
PREPARE preset_node_lng_num_stmt FROM @preset_node_lng_num_sql;
EXECUTE preset_node_lng_num_stmt;
DEALLOCATE PREPARE preset_node_lng_num_stmt;

SET @preset_node_lat_num_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'preset_route_node' AND column_name = 'lat_num'
);
SET @preset_node_lat_num_sql := IF(
    @preset_node_lat_num_exists = 0,
    'ALTER TABLE preset_route_node ADD COLUMN lat_num DECIMAL(10,7) GENERATED ALWAYS AS (CASE WHEN TRIM(latitude) REGEXP ''^-?[0-9]{1,2}([.][0-9]+)?$'' AND CAST(TRIM(latitude) AS DECIMAL(20,12)) BETWEEN -90 AND 90 THEN CAST(TRIM(latitude) AS DECIMAL(10,7)) END) STORED COMMENT ''纬度数值(由 latitude 派生,只读)''',
    'SELECT 1'
);
PREPARE preset_node_lat_num_stmt FROM @preset_node_lat_num_sql;
EXECUTE preset_node_lat_num_stmt;
DEALLOCATE PREPARE preset_node_lat_num_stmt;

SET @preset_node_geo_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'preset_route_node' AND index_name = 'idx_preset_node_geo'
);
SET @preset_node_geo_idx_sql := IF(
    @preset_node_geo_idx_exists = 0,
    'ALTER TABLE preset_route_node ADD INDEX idx_preset_node_geo (lat_num, lng_num)',
    'SELECT 1'
);
PREPARE preset_node_geo_idx_stmt FROM @preset_node_geo_idx_sql;
EXECUTE preset_node_geo_idx_stmt;
DEALLOCATE PREPARE preset_node_geo_idx_stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'preset_route_node'
          AND column_name IN ('lng_num', 'lat_num') AND column_type = 'decimal(10,7)'
          AND extra LIKE '%STORED GENERATED%') = 2
   AND (SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'preset_route_node'
          AND index_name = 'idx_preset_node_geo') = 2 AS assertion_ok;
