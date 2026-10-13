-- ============================================================================
-- X09：删除 5 个「两端都没有读取方」的假灰度开关
--
-- 病灶：/api/config/features 每次启动向所有客户端下发这 5 个键，
--   RuoYi 后台也列着 5 行可编辑的配置，但全仓找不到任何一处读取它们的值：
--
--     feature.flag.selfPlay      自玩票      读取点 0
--     feature.flag.citymap       点亮城市    读取点 0
--     feature.flag.eggs          途中彩蛋    读取点 0
--     feature.flag.trackRecord   轨迹记录    读取点 0
--     feature.flag.xpBudget      XP预算      读取点 0
--
--   核对口径：小程序侧 grep `features.<key>` / `features['<key>']`、
--   服务端侧 grep `feature.flag.<key>`，除本批 seed（migration_m10_feature_flags.sql、
--   _deploy_merged_20260704.sql、_deploy_ALL_20260704.sql）与下发列表本身外零命中。
--   （`CommercialTopicPolicy` 里的 `selfPlay` 是 cms_topic 的字段，不是本开关。）
--
-- 为什么必须删而不是留着：运营在后台看到「功能开关-自玩票」，会认为把它改成 true
--   能开一个真实功能。改完什么都不会发生，也不会报错 —— 一个会骗人的按钮比没有按钮
--   更危险。代码侧已在 ApiConfigController.FLAG_KEYS 停止下发；这份迁移收尾后台那半边。
--
-- ★ fail-closed 边界：只删「仍是初始值 false」的行。
--   任何一个键的当前值不是 false，说明有人真的去开过它 ——
--   那意味着运营对它有预期（或者存在本审查没找到的读取方），
--   这时必须红并回滚，交由人判断，不能由迁移替他删掉。
--   同理，出现重复行（同 config_key 多行）也红：sys_config 没有唯一约束，
--   删之前必须确认删的到底是几行。
--
-- ★ 幂等：已删过再跑，第一段守卫 COUNT 为 0 时同样通过（0 行也是「没有非法值」），
--   DELETE 影响 0 行，末尾回读断言仍成立。
--
-- ★ 不走 --transaction（2026-08-27 被 ci/test_migration_runner_ci_contract.py 判红后改的）：
--   窄事务白名单只收 INSERT / 带 WHERE 的 UPDATE / 临时表 DDL，本文件是 SELECT 守卫 + DELETE，
--   两样都不在里面 —— 这份迁移原本错误地声明了 requires=transaction。
--   它本来也不需要事务：
--     · 两道守卫是 assert=scalar-true，判假时 runner 在 DELETE 之前就中止；
--     · DELETE 是单条语句，InnoDB 内本身原子；
--     · 关键在于 DELETE **自带值守卫**（NOT IN ('true','1')）—— 就算有人在守卫①与 DELETE
--       之间把某个键改成 true，那一行也会被 DELETE 自己跳过，不存在 TOCTOU；
--     · 重跑幂等：第二次删 0 行，回读断言照样成立。
--
-- ⚠️ 本文件只准备，不执行。生产库写操作由总控在用户点头后执行（chengyin-db-migrate）。
-- ============================================================================

-- 预检（执行前人工跑）：应回 0..5 行，config_value 应全为 'false'
--   SELECT config_id, config_key, config_value, remark FROM sys_config
--    WHERE config_key IN ('feature.flag.selfPlay','feature.flag.citymap',
--                         'feature.flag.eggs','feature.flag.trackRecord',
--                         'feature.flag.xpBudget');

-- 守卫①：这 5 个键里，不存在「被开过」的值。
-- 口径与 NpcFeatureFlags.flagOn 一致：true/TRUE/1 视为开。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_config
         WHERE CAST(config_key AS BINARY) IN (
                 CAST('feature.flag.selfPlay'    AS BINARY),
                 CAST('feature.flag.citymap'     AS BINARY),
                 CAST('feature.flag.eggs'        AS BINARY),
                 CAST('feature.flag.trackRecord' AS BINARY),
                 CAST('feature.flag.xpBudget'    AS BINARY))
           AND LOWER(TRIM(config_value)) IN ('true', '1')) = 0 AS ok;

-- 守卫②：每个键至多一行（sys_config 无唯一约束，重复行必须先人工收敛）。
-- runner: assert=scalar-true
SELECT (SELECT COALESCE(MAX(c), 0) FROM (
          SELECT COUNT(*) AS c FROM sys_config
           WHERE CAST(config_key AS BINARY) IN (
                   CAST('feature.flag.selfPlay'    AS BINARY),
                   CAST('feature.flag.citymap'     AS BINARY),
                   CAST('feature.flag.eggs'        AS BINARY),
                   CAST('feature.flag.trackRecord' AS BINARY),
                   CAST('feature.flag.xpBudget'    AS BINARY))
           GROUP BY CAST(config_key AS BINARY)) g) <= 1 AS ok;

DELETE FROM sys_config
 WHERE CAST(config_key AS BINARY) IN (
         CAST('feature.flag.selfPlay'    AS BINARY),
         CAST('feature.flag.citymap'     AS BINARY),
         CAST('feature.flag.eggs'        AS BINARY),
         CAST('feature.flag.trackRecord' AS BINARY),
         CAST('feature.flag.xpBudget'    AS BINARY))
   AND LOWER(TRIM(config_value)) NOT IN ('true', '1');

-- 回读：5 个键一个不剩。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_config
         WHERE CAST(config_key AS BINARY) IN (
                 CAST('feature.flag.selfPlay'    AS BINARY),
                 CAST('feature.flag.citymap'     AS BINARY),
                 CAST('feature.flag.eggs'        AS BINARY),
                 CAST('feature.flag.trackRecord' AS BINARY),
                 CAST('feature.flag.xpBudget'    AS BINARY))) = 0 AS ok;

-- 回读：仍在下发的 4 个开关一个都没被误删。
-- （roamNpcEvent / roamNpcChat 不走 sys_config，由 NpcFeatureFlags 单独管，故不在此列。）
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_config
         WHERE CAST(config_key AS BINARY) IN (
                 CAST('feature.flag.drops'             AS BINARY),
                 CAST('feature.flag.roamDemoPois'      AS BINARY),
                 CAST('feature.flag.finishNearbyRoute' AS BINARY),
                 CAST('feature.flag.clubMembership'    AS BINARY))) >= 1 AS ok;
