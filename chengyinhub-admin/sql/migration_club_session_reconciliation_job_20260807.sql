-- 俱乐部合作主题缺带队场次巡检：只读 + 告警，不补写活动、不动资金。
-- 依赖：含 clubSessionReconciliationTask bean 的 jar 须在下一次 03:20 调度前部署。
INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
     create_by, create_time, remark)
SELECT '俱乐部带队场次对账', 'DEFAULT', 'clubSessionReconciliationTask.run()', '0 20 3 * * ?',
       '3', '1', '0', 'admin', NOW(),
       '每日扫描生效club合作单但无topic+club带队场次的主题；只告警，不自动补场次'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target = 'clubSessionReconciliationTask.run()'
);
