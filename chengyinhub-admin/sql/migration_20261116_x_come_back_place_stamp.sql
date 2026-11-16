-- GAME-12 (x-come-back) 地点盖章:roam_stamp.poi_id + uk_member_poi(每人每地点一枚)。
-- 回退:保留列与索引(旧 jar 不读写 poi_id;唯一键只约束非 NULL 行,自由拍照片 poi_id 为 NULL 不受影响)。
--       禁止 DROP —— 会丢掉玩家已盖章的地点。功能开关 chengyin.roam.place-stamp.enabled 默认 false,关着时不写 poi_id。
-- 执行方式:走 chengyin-db-migrate,部署新 jar 前先落库(新 jar 的 insertStamp 写 poi_id 列)。
-- 可重复执行:加列、加索引先查 information_schema。不回填 —— 存量邮票都是自由拍(kind=0)。

SET @rs_poi_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='roam_stamp' AND column_name='poi_id');
SET @rs_poi_col_sql := IF(@rs_poi_col_exists=0,
    'ALTER TABLE roam_stamp ADD COLUMN poi_id BIGINT NULL DEFAULT NULL COMMENT ''地点盖章(kind=1)的地点 id;自由拍为 NULL'' AFTER geohash7',
    'SELECT 1');
PREPARE rs_poi_col_stmt FROM @rs_poi_col_sql;
EXECUTE rs_poi_col_stmt;
DEALLOCATE PREPARE rs_poi_col_stmt;

SET @rs_poi_uk_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='roam_stamp' AND index_name='uk_member_poi');
SET @rs_poi_uk_sql := IF(@rs_poi_uk_exists=0,
    'ALTER TABLE roam_stamp ADD UNIQUE KEY uk_member_poi (member_id, poi_id)',
    'SELECT 1');
PREPARE rs_poi_uk_stmt FROM @rs_poi_uk_sql;
EXECUTE rs_poi_uk_stmt;
DEALLOCATE PREPARE rs_poi_uk_stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='roam_stamp' AND column_name='poi_id')=1
   AND (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='roam_stamp' AND index_name='uk_member_poi')>=1 AS rs_place_stamp_ready;
