-- 售后超时转平台扫描任务自动注册(幂等:按 invoke_target 去重)。
-- cron 0 0/10 * * * ? = 每 10 分钟;concurrent=1 禁止并发;misfire_policy=3 放弃错过的执行;status=0 正常。
-- ⚠️ 任务没注册 = jar 部署了也永不调度,且不报错零告警(见 ci/migration_gates.py 病历)。
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '售后-商家超时转平台客服', 'DEFAULT', 'merchantAftercareTimeoutTask.run()', '0 0/10 * * * ?', '3', '1', '0', 'merchant-aftercare-migrate', NOW(), '拍板2026-09-16#12 MerchantAftercareTimeoutTask'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'merchantAftercareTimeoutTask.run()');

-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM sys_job
WHERE invoke_target = 'merchantAftercareTimeoutTask.run()'
  AND cron_expression = '0 0/10 * * * ?'
  AND status = '0';

-- 回滚(确认没有查询依赖后):
-- DELETE FROM sys_job WHERE invoke_target = 'merchantAftercareTimeoutTask.run()';
