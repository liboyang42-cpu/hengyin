-- ================= migration_20261023_points_value_config_seed.sql =================
SELECT 'migration_20261023_points_value_config_seed.sql' AS running;
-- owner 2026-10-23 D1(审计 P0-01):积分价值唯一服务端真源落种。两个键的值 = 代码现行默认(键缺失时的行为),
-- 落种后报名价格不变:
--   points.deduct.rate_yuan_per_point  '0.02'  = 1 积分折合 0.02 元(原 CmsConstants.POINT_DEDUCT_RATE)
--   points.deduct.max_yuan_per_order   '50.00' = 单笔订单积分最多折合 50 元(原 CmsConstants.MAX_POINT_DEDUCT_AMOUNT)
-- 读方:PointsValuePolicy(报名报价/下单 + 积分商城结算)。值缺失、为空或不是正数时代码按上述缺省值走。
-- 只在键缺失时插入,不覆盖运维已配置的值,可重复执行。依赖:sys_config(RuoYi 基础表)。自然键 = config_key。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
--
-- 落库前只读预检:
--   SELECT config_key, config_value FROM sys_config
--   WHERE config_key IN ('points.deduct.rate_yuan_per_point', 'points.deduct.max_yuan_per_order');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '积分-折合汇率(元/分)', 'points.deduct.rate_yuan_per_point', '0.02', 'N', 'admin', NOW(),
       '1 积分折合多少元;报名抵扣与积分商城共用。缺省 0.02(50 积分 = 1 元)。必须为正数,否则按 0.02 处理'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'points.deduct.rate_yuan_per_point');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '积分-单笔抵扣上限(元)', 'points.deduct.max_yuan_per_order', '50.00', 'N', 'admin', NOW(),
       '单笔订单积分最多折合多少元;报名抵扣与积分商城共用。缺省 50.00。必须为正数,否则按 50.00 处理'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'points.deduct.max_yuan_per_order');

-- 回读断言:两个键各恰好一行(不校验值 —— 运维事先配置过的值保持原样)。
-- runner: assert=scalar-true
SELECT SUM(config_key = 'points.deduct.rate_yuan_per_point') = 1
   AND SUM(config_key = 'points.deduct.max_yuan_per_order') = 1 AS assertion_ok
FROM sys_config
WHERE config_key IN ('points.deduct.rate_yuan_per_point', 'points.deduct.max_yuan_per_order');
