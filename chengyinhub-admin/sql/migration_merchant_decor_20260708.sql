-- 店铺装修 E0：mms_merchant 增展示层 8 列(全可空,可回滚)
-- 关联设计：Desktop/Questify_商家店铺装修_最终设计与TODO_20260708.md §9
-- 已有列勿重加：logo/name/description/derivatives/website/category_id/business_time/business_status/capacity/available_time/suit_activity_types/charge_type/demand/coop_open
-- 行业类型复用 category_id + sys_category(不新增列)

-- 幂等守卫:以 cover_image 为哨兵列,已存在则整段跳过(MySQL 8 无 ADD COLUMN IF NOT EXISTS),避免重跑 1060 中断
SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'mms_merchant' AND column_name = 'cover_image');
SET @sql := IF(@exist = 0,
  'ALTER TABLE mms_merchant
     ADD COLUMN cover_image   VARCHAR(255) NULL COMMENT ''店铺装修-封面头图URL'',
     ADD COLUMN slogan        VARCHAR(60)  NULL COMMENT ''店铺装修-一句话slogan'',
     ADD COLUMN city_role     VARCHAR(30)  NULL COMMENT ''店铺装修-城市角色名'',
     ADD COLUMN story_title   VARCHAR(60)  NULL COMMENT ''店铺装修-品牌故事标题(正文用description)'',
     ADD COLUMN gallery       TEXT         NULL COMMENT ''店铺装修-门店相册JSON数组'',
     ADD COLUMN tags          VARCHAR(255) NULL COMMENT ''店铺装修-特色标签JSON/逗号'',
     ADD COLUMN featured_type TINYINT      NULL DEFAULT 0 COMMENT ''店铺装修-招牌主推类型0无/1活动主题/2券'',
     ADD COLUMN featured_id   BIGINT       NULL COMMENT ''店铺装修-招牌主推目标id''',
  'SELECT ''mms_merchant 装修列已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回滚:
-- ALTER TABLE mms_merchant
--   DROP COLUMN cover_image, DROP COLUMN slogan, DROP COLUMN city_role,
--   DROP COLUMN story_title, DROP COLUMN gallery, DROP COLUMN tags,
--   DROP COLUMN featured_type, DROP COLUMN featured_id;
