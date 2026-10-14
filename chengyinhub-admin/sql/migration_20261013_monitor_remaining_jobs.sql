-- F-W3-04: register the remaining scheduled jobs whose only registration SQL predates the 2026-08-18
-- base snapshot (so a rebuilt environment never had them). Missing rows only; never update, enable or
-- reschedule an existing row (operators may have paused or re-planned it deliberately).
-- Reconciliation jobs that only alarm or reconcile from WeChat terminal state are registered enabled,
-- matching their original migrations. Jobs that move money or message users are registered PAUSED in an
-- environment where they were missing: the funds/ops owner enables them after checking the target.
-- RuoYi loads sys_job at startup only: rows inserted here take effect after the next restart.
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name='sys_job' AND table_type='BASE TABLE')=1
 AND (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='sys_job'
 AND column_name IN ('job_id','job_name','job_group','invoke_target','cron_expression',
 'misfire_policy','concurrent','status','create_by','create_time','remark'))=11;
-- Fail before inserting anything when a target already has duplicate rows. Keep all original rows.
-- runner: assert=scalar-true
SELECT COUNT(*)=0 FROM (
 SELECT CAST(invoke_target AS BINARY) AS exact_target FROM sys_job
 WHERE CAST(invoke_target AS BINARY) IN (CAST('refundReconcileTask.reconcile()' AS BINARY),
 CAST('clubSessionReconciliationTask.run()' AS BINARY), CAST('settlementTask.run()' AS BINARY),
 CAST('coopSettlementPayoutTask.run()' AS BINARY), CAST('beforeStartNotifyTask.run()' AS BINARY))
 GROUP BY CAST(invoke_target AS BINARY) HAVING COUNT(*)>1
) duplicate_targets;
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '退款对账兜底','DEFAULT','refundReconcileTask.reconcile()','0 0/15 * * * ?','3','1','0','system',NOW(),
 '仅补缺失登记(原 seed_refund_manual_menu_20260714)；按微信终态回写卡在处理中的退款并告警积压'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('refundReconcileTask.reconcile()' AS BINARY));
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '俱乐部带队场次对账','DEFAULT','clubSessionReconciliationTask.run()','0 20 3 * * ?','3','1','0','system',NOW(),
 '仅补缺失登记(原 migration_club_session_reconciliation_job_20260807)；只告警，不自动补场次'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('clubSessionReconciliationTask.run()' AS BINARY));
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '分润结算（活动+主题）','DEFAULT','settlementTask.run()','0 0 * * * ?','3','1','1','system',NOW(),
 '仅补缺失登记且默认暂停：资金任务须由资金owner核对目标环境后在「定时任务」页启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('settlementTask.run()' AS BINARY));
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '商家结算T+7打款','DEFAULT','coopSettlementPayoutTask.run()','0 0 3 * * ?','3','1','1','system',NOW(),
 '仅补缺失登记且默认暂停：会真实出款，须由资金owner核对目标环境与商户号后启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('coopSettlementPayoutTask.run()' AS BINARY));
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '出发前提醒','DEFAULT','beforeStartNotifyTask.run()','0 0 * * * ?','3','1','1','system',NOW(),
 '仅补缺失登记且默认暂停：会向用户发订阅消息，须核对订阅模板与目标环境后启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('beforeStartNotifyTask.run()' AS BINARY));
-- runner: assert=scalar-true
SELECT COUNT(*)=5 AND COUNT(DISTINCT CAST(invoke_target AS BINARY))=5 FROM sys_job
WHERE CAST(invoke_target AS BINARY) IN (CAST('refundReconcileTask.reconcile()' AS BINARY),
 CAST('clubSessionReconciliationTask.run()' AS BINARY), CAST('settlementTask.run()' AS BINARY),
 CAST('coopSettlementPayoutTask.run()' AS BINARY), CAST('beforeStartNotifyTask.run()' AS BINARY));
