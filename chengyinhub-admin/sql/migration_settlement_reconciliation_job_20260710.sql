-- 结算批次金额一致性巡检：仅告警，不改账；依赖含 SettlementReconciliationTask 的新 jar 已部署。
-- 默认暂停，完成一次人工核验并配置 alarm.webhook 后再在后台启用。
INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '结算批次金额巡检', 'DEFAULT', 'settlementReconciliationTask.run()', '0 10 3 * * ?', '3', '1', '1', 'admin', NOW(),
       '巡检 settlement_batch 与 settlement_detail 金额差异，仅告警，默认暂停'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'settlementReconciliationTask.run()');
