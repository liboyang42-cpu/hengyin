-- ================= migration_20261024_community_open_flags.sql =================
SELECT 'migration_20261024_community_open_flags.sql' AS running;
-- owner 2026-10-24:上线即对所有玩家开放专业广场(动态)。读方:CommunityPostFeatureGate。
--   feature.flag.communityPostRead          'true' = 打开浏览(访客也可浏览)
--   feature.flag.communityPostWrite         'true' = 打开发帖(须登录)
--   feature.flag.communityPostPilotMembers  '*'    = 全量,不再按账号白名单
-- 三个键缺失时网关一律按关闭处理;本迁移只在键缺失时插入,运维已手工配过的值保持原样,可重复执行。
-- 关闭方法:后台「参数设置」把前两个键改成 false(无需发版)。
--
-- 落库前只读预检:
--   SELECT config_key, config_value FROM sys_config WHERE config_key IN
--   ('feature.flag.communityPostRead','feature.flag.communityPostWrite','feature.flag.communityPostPilotMembers');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '专业广场-浏览开关', 'feature.flag.communityPostRead', 'true', 'N', 'admin', NOW(),
       'true=开放浏览;false=关闭。与 feature.flag.communityPostPilotMembers 同时生效'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.communityPostRead');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '专业广场-发帖开关', 'feature.flag.communityPostWrite', 'true', 'N', 'admin', NOW(),
       'true=开放发帖(须登录);false=关闭。与 feature.flag.communityPostPilotMembers 同时生效'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.communityPostWrite');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '专业广场-灰度账号', 'feature.flag.communityPostPilotMembers', '*', 'N', 'admin', NOW(),
       '*=全部用户;逗号分隔的会员 ID=仅这些账号;留空=全部关闭'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.communityPostPilotMembers');

-- 回读断言:三个键各恰好一行(不校验值 —— 运维事先配置过的值保持原样)。
-- runner: assert=scalar-true
SELECT SUM(config_key = 'feature.flag.communityPostRead') = 1
   AND SUM(config_key = 'feature.flag.communityPostWrite') = 1
   AND SUM(config_key = 'feature.flag.communityPostPilotMembers') = 1 AS assertion_ok
FROM sys_config
WHERE config_key IN ('feature.flag.communityPostRead', 'feature.flag.communityPostWrite', 'feature.flag.communityPostPilotMembers');
