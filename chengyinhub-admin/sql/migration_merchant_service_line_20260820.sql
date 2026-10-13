-- 商家卡「服务行」:mms_merchant 增服务展示 2 列(全可空,可回滚)
-- 关联:2026-08-20 商家卡重设计(feat/card-redesign),cy-merchant-card 服务行
--   service_tag  = 短标签(如 ¥5 / 免费),service_text = 服务文案(如 健身体验卡)
--   两列均空 = 商家无服务展示,前端整行隐藏
-- 消费方:/api/merchant/list 自动带出;编辑入口 = /api/merchant/decor/save 白名单

-- 幂等守卫:以 service_tag 为哨兵列,已存在则整段跳过(MySQL 8 无 ADD COLUMN IF NOT EXISTS),避免重跑 1060 中断
SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'mms_merchant' AND column_name = 'service_tag');
SET @sql := IF(@exist = 0,
  'ALTER TABLE mms_merchant
     ADD COLUMN service_tag  VARCHAR(8)  NULL COMMENT ''店铺装修-服务行短标签(如¥5/免费)'',
     ADD COLUMN service_text VARCHAR(64) NULL COMMENT ''店铺装修-服务行文案(如健身体验卡),空=不展示服务行''',
  'SELECT ''mms_merchant 服务行列已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回滚:
-- ALTER TABLE mms_merchant DROP COLUMN service_tag, DROP COLUMN service_text;
