-- mms_merchant.location_verified 增加第三档:2=按地址反查推断。
--
-- 原注释是「0未定位 1已定位」两档。入驻链路现在会在商家只填了地址、没点地图时
-- 用 LBS 反查补一个坐标(见 ApiMerchantController#applyLocation),这种行「有坐标」
-- 但没人确认过,两档都装不下:记 0 等于说它没坐标,记 1 等于冒充商家亲自点的。
--
-- 只改列注释,不动任何数据 —— 存量行的取值语义没变(0 仍是没坐标,1 仍是商家点的)。
-- 存量 10 行的坐标回填另行处理,回填写入时用 2。

-- 幂等:注释已含「2」则跳过,可重复执行
SET @done := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'mms_merchant'
                AND column_name = 'location_verified' AND column_comment LIKE '%2%');
SET @sql := IF(@done = 0,
  'ALTER TABLE mms_merchant
     MODIFY COLUMN location_verified TINYINT NULL DEFAULT 0
     COMMENT ''0未定位 1商家地图选点 2按地址反查推断''',
  'SELECT ''location_verified 注释已含 2,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回滚: ALTER TABLE mms_merchant MODIFY COLUMN location_verified TINYINT NULL DEFAULT 0 COMMENT '0未定位 1已定位';
