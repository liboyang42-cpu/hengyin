-- POST_DEPLOY only: exact candidate jar/bean health and independent migration approval are required.
-- No current instance is registered/started by creating this file. Never update existing paused/duplicate jobs.
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name='sys_job' AND table_type='BASE TABLE')=1
 AND (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='sys_job'
 AND column_name IN ('job_id','job_name','job_group','invoke_target','cron_expression',
 'misfire_policy','concurrent','status','create_by','create_time','remark'))=11
 AND (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='sys_job' AND column_name='job_id'
 AND column_key='PRI' AND extra LIKE '%auto_increment%')=1;
-- Fail before inserting anything when a target already has duplicate rows. Keep all original rows.
-- runner: assert=scalar-true
SELECT COUNT(*)=0 FROM (
 SELECT CAST(invoke_target AS BINARY) AS exact_target FROM sys_job
 WHERE CAST(invoke_target AS BINARY) IN (CAST('metricsProbeTask.run()' AS BINARY),
 CAST('auditBacklogMonitorTask.run()' AS BINARY), CAST('withdrawReconciliationTask.run()' AS BINARY),
 CAST('settlementReconciliationTask.run()' AS BINARY))
 GROUP BY CAST(invoke_target AS BINARY) HAVING COUNT(*)>1
) duplicate_targets;
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT 'D12指标采样','DEFAULT','metricsProbeTask.run()','0 0/5 * * * ?','3','1','0','system',NOW(),
 '仅补真正缺失的巡检登记；POST_DEPLOY须核候选bean与健康并独立批准'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('metricsProbeTask.run()' AS BINARY));
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT 'UGC审核积压监控','COMPLIANCE','auditBacklogMonitorTask.run()','0 0/30 * * * ?','3','1','0','system',NOW(),
 '仅补真正缺失的巡检登记；不覆盖已暂停或已配置任务'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('auditBacklogMonitorTask.run()' AS BINARY));
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '提现出金日对账','FINANCE','withdrawReconciliationTask.run()','0 0 4 * * ?','3','1','0','system',NOW(),
 '纯聚合/告警，不出款不回补；仅補真正缺失的登记'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('withdrawReconciliationTask.run()' AS BINARY));
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '结算批次金额巡检','DEFAULT','settlementReconciliationTask.run()','0 10 3 * * ?','3','1','0','system',NOW(),
 '只核对金额/告警，不改资金；已存在暂停任务保持暂停'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('settlementReconciliationTask.run()' AS BINARY));
-- Existing rows may deliberately remain paused or use an operator-approved schedule. Do not normalize them.
-- runner: assert=scalar-true
SELECT COUNT(*)=4 AND COUNT(DISTINCT CAST(invoke_target AS BINARY))=4 FROM sys_job
WHERE CAST(invoke_target AS BINARY) IN (CAST('metricsProbeTask.run()' AS BINARY),
 CAST('auditBacklogMonitorTask.run()' AS BINARY), CAST('withdrawReconciliationTask.run()' AS BINARY),
 CAST('settlementReconciliationTask.run()' AS BINARY));
