-- ================= migration_20261022_location_guard_switch_seed.sql =================
SELECT 'migration_20261022_location_guard_switch_seed.sql' AS running;
-- Doc2 F1c(O-01 / O-02)开关落种。三个键的值 = 代码现行默认(键缺失时的行为),落种后行为不变:
--   play.arrive.location_guard.mode    'off'   = /arrive 节流/速度/窗口位移守卫关闭;shadow 只记不拦;enforce 拦截
--   play.arrive.require_accuracy       'false' = 旧版小程序不报精度照常放行(owner 定最低版本前不要改 true)
--   roam.visited_poi.stable_identity   'false' = 点到店仍用 geo_key;改 true 前须先落 poi_key 列 + 回填迁移
-- 只在键缺失时插入,不覆盖运维已配置的值,可重复执行。依赖:sys_config(RuoYi 基础表)。自然键 = config_key。
-- 开关只由后台(sys_config)改,端上不写。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
--
-- 落库前只读预检:
--   SELECT config_key, config_value FROM sys_config
--   WHERE config_key IN ('play.arrive.location_guard.mode', 'play.arrive.require_accuracy',
--                        'roam.visited_poi.stable_identity');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '到达守卫-模式', 'play.arrive.location_guard.mode', 'off', 'N', 'admin', NOW(),
       'off=关闭(默认);shadow=只记录本应拒绝的到达不拦截;enforce=节流5s/速度8m/s/60分钟40km 拦截,被拒可申诉'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'play.arrive.location_guard.mode');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '到达守卫-要求上报精度', 'play.arrive.require_accuracy', 'false', 'N', 'admin', NOW(),
       'false=未上报定位精度的旧版小程序照常放行(默认);true=拒绝并提示更新或扫码。定下小程序最低版本前保持 false'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'play.arrive.require_accuracy');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '点到店-稳定身份', 'roam.visited_poi.stable_identity', 'false', 'N', 'admin', NOW(),
       'false=按3位小数坐标去重(默认);true=按地标id/首访锚点去重。打开前须先执行 poi_key 加列与回填迁移'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'roam.visited_poi.stable_identity');

-- 回读断言:三个键各恰好一行(不校验值 —— 运维事先配置过的值保持原样)。
-- runner: assert=scalar-true
SELECT SUM(config_key = 'play.arrive.location_guard.mode') = 1
   AND SUM(config_key = 'play.arrive.require_accuracy') = 1
   AND SUM(config_key = 'roam.visited_poi.stable_identity') = 1 AS assertion_ok
FROM sys_config
WHERE config_key IN ('play.arrive.location_guard.mode', 'play.arrive.require_accuracy',
                     'roam.visited_poi.stable_identity');
