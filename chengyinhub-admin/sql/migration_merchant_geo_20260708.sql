-- 商家 geo(E2 采集):mms_merchant 加经纬度,解锁 searchmap 商家图层 / 附近商家按距离
-- 采集方式:装修页手动打点(wx.chooseLocation)为主;LBS 地址转坐标兜底为后续

-- 幂等守卫:以 location_lat 为哨兵列,已存在则整段跳过(MySQL 8 无 ADD COLUMN IF NOT EXISTS),避免重跑 1060 中断
SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'mms_merchant' AND column_name = 'location_lat');
SET @sql := IF(@exist = 0,
  'ALTER TABLE mms_merchant
     ADD COLUMN location_lat DECIMAL(10,7) NULL COMMENT ''商家纬度(手动打点/LBS)'',
     ADD COLUMN location_lng DECIMAL(10,7) NULL COMMENT ''商家经度'',
     ADD COLUMN location_verified TINYINT NULL DEFAULT 0 COMMENT ''0未定位 1已定位''',
  'SELECT ''mms_merchant geo 列已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回滚: ALTER TABLE mms_merchant DROP COLUMN location_lat, DROP COLUMN location_lng, DROP COLUMN location_verified;
