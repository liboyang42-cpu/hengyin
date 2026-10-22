-- ================= migration_20261022_audit_activity_switch_seed.sql =================
SELECT 'migration_20261022_audit_activity_switch_seed.sql' AS running;
-- #376 活动分级审核总闸落种:ActivityReviewPolicy 读这两个 sys_config 键,但此前没有任何 migration 落种,
-- 键不存在时回滚总闸 / 高风险类目在生产上无从配置(只能先手工 INSERT)。
--   audit.activity.pre_review_all        'false' = 不开总闸(活动默认先发后审);改 'true' 一键退回全量先审
--   audit.activity.high_risk_categories  ''      = 无高风险类目;逗号分隔类目 id 命中即先审
-- 值 = 代码现行默认(键缺失时的行为),落种后行为不变。只在键缺失时插入,不覆盖运维已配置的值,可重复执行。
-- 依赖:sys_config(RuoYi 基础表)。自然键 = config_key。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
--
-- 落库前只读预检:
--   SELECT config_key, config_value FROM sys_config
--   WHERE config_key IN ('audit.activity.pre_review_all', 'audit.activity.high_risk_categories');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '活动审核-全量先审总闸', 'audit.activity.pre_review_all', 'false', 'N', 'admin', NOW(),
       'true=活动一律先审后发(灰度回滚阀);false=按类目/主体分级,默认先发后审'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'audit.activity.pre_review_all');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '活动审核-高风险类目', 'audit.activity.high_risk_categories', '', 'N', 'admin', NOW(),
       '逗号分隔的活动类目 id,命中即先审后发(未成年/医疗/金融/危险运动/涉水涉火);空=无'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'audit.activity.high_risk_categories');

-- 回读断言:两个键各恰好一行(不校验值 —— 运维事先配置过的值保持原样)。
-- runner: assert=scalar-true
SELECT SUM(config_key = 'audit.activity.pre_review_all') = 1
   AND SUM(config_key = 'audit.activity.high_risk_categories') = 1 AS assertion_ok
FROM sys_config
WHERE config_key IN ('audit.activity.pre_review_all', 'audit.activity.high_risk_categories');
