-- CITY-6 (W7 x-city-monopoly, 2026-11-16): City Monopoly overnight jobs. Before this, settle / nightly / season settle
-- ran only when an admin pressed the console buttons, so rent stayed PENDING for ever and upkeep / decay never ran.
-- 【回滚】(先回滚 jar,再执行):
--   DELETE FROM sys_job WHERE invoke_target IN ('cityMonopolyTask.settle()', 'cityMonopolyTask.nightly()', 'cityMonopolyTask.seasonSettle()');
-- 【上线顺序】POST_DEPLOY:三行指向只有新 jar 才有的 bean cityMonopolyTask(ci/migration_ledger.py POST_DEPLOY_MIGRATIONS)。
-- 【幂等】INSERT ... WHERE NOT EXISTS;运营改过的 cron 不覆盖。每一步按天/事件/赛季幂等,重复触发不会重复入账。
-- 【秒位】直接登记错峰后的秒位(44 / 50 / 56),计划行在 migration_20261127_stagger_city_monopoly_jobs.sql。

INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '城市大富翁:收租结算(待核验→积分)', 'DEFAULT', 'cityMonopolyTask.settle()', '44 3 3 * * ?', '3', '1', '0',
       'system', NOW(), 'CITY-6;每天 03:03,前一天及更早的待核验租金入账或作废'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'cityMonopolyTask.settle()');

INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '城市大富翁:夜间维护(旧领地转换/维护费/衰退/风控)', 'DEFAULT', 'cityMonopolyTask.nightly()', '50 23 3 * * ?', '3', '1', '0',
       'system', NOW(), 'CITY-6/CITY-9;每天 03:23,按天幂等'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'cityMonopolyTask.nightly()');

INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '城市大富翁:赛季结算', 'DEFAULT', 'cityMonopolyTask.seasonSettle()', '56 7 9 ? * MON', '3', '1', '0',
       'system', NOW(), 'CITY-6;每周一 09:07 结算上一赛季一次,已结算则跳过'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'cityMonopolyTask.seasonSettle()');

-- 只读核验:
-- SELECT job_id, invoke_target, cron_expression, status FROM sys_job WHERE invoke_target LIKE 'cityMonopolyTask.%';
