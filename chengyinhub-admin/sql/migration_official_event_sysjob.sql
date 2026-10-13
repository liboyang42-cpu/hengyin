-- ============================================================================
-- 官方活动定时任务:活动状态自动流转(到点结算发奖)+ 定时通知发送。
-- 幂等:按 invoke_target 去重。⚠️ RuoYi Quartz 调度器在 **jar 重启时**装载 sys_job,
--       故本条须与新 jar 部署(重启)配套才真正开始跑;单跑本 SQL 不会即时调度。
-- 对应 Task 类:chengyinhub-admin OfficialEventTask#run()(officialEventService.runStatusFlow + broadcast.runDueScheduled)。
-- status='0' 正常启用;concurrent='1' 禁止并发;misfire_policy='3' 放弃。
-- ============================================================================
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '官方活动状态流转+结算', 'DEFAULT', 'officialEventTask.run()', '0 0/5 * * * ?', '3', '1', '0', 'admin', NOW(), '官方活动到点自动结算发奖 + 定时通知发送'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'officialEventTask.run()');
