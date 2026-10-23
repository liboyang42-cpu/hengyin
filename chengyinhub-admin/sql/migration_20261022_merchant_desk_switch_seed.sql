-- ================= migration_20261022_merchant_desk_switch_seed.sql =================
SELECT 'migration_20261022_merchant_desk_switch_seed.sql' AS running;
-- Doc2 F4 商家工作台开关落种。值 = 代码现行默认(键缺失时的行为),落种后行为不变:
--   merchant.desk.f4.enabled  'false' = /api/merchant/desk/* 全部返回 F4_DISABLED,不读不写任何新表
-- 打开(true)之前必须先落同批四个建表迁移:merchant_desk_receipt / merchant_redemption_anchor /
--   pms_sku_booking / verification_review_evidence。
-- 只在键缺失时插入,不覆盖运维已配置的值,可重复执行。依赖:sys_config(RuoYi 基础表)。自然键 = config_key。
-- 开关只由后台(sys_config)改,端上不写。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
--
-- 落库前只读预检:
--   SELECT config_key, config_value FROM sys_config WHERE config_key = 'merchant.desk.f4.enabled';

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '商家工作台F4-总开关', 'merchant.desk.f4.enabled', 'false', 'N', 'admin', NOW(),
       'false=关闭(默认,接口返回 F4_DISABLED);true=开放门店/商品SKU/核销台/复核补材料/对账只读/锚点配置。打开前须先落四个建表迁移'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'merchant.desk.f4.enabled');

-- 回读断言:键恰好一行(不校验值 —— 运维事先配置过的值保持原样)。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM sys_config WHERE config_key = 'merchant.desk.f4.enabled';
