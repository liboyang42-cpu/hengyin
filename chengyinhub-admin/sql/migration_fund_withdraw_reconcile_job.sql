-- 提现出金日对账/健康巡检 sys_job。
-- ★后于 jar(依赖 withdrawReconciliationTask bean)。幂等:先删同 invoke_target 再插入。
-- 每日 04:00 跑;纯 DB 聚合无微信调用。
DELETE FROM sys_job WHERE invoke_target = 'withdrawReconciliationTask.run()';
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_time)
VALUES ('提现出金日对账', 'FINANCE', 'withdrawReconciliationTask.run()', '0 0 4 * * ?', '3', '1', '0', NOW());
