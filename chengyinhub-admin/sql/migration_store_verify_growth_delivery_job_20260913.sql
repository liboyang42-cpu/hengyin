-- 阶段：post-deploy；先完成同日delivery表结构迁移，再观测包含此bean的新jar，最后注册。
-- 注册后必须通过现有定时任务管理的启用操作（/monitor/job/changeStatus，status=0）加载运行中Scheduler。
-- 本文件断言仅证明数据库配置；须另查该任务实际执行日志及待办恢复结果，不得据此认定调度已生效。
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '核销成长奖励恢复','DEFAULT','storeVerifyGrowthTask.run()','0 * * * * ?', '3','1','0','system',NOW(),
       '每分钟最多50条，恢复已入队核销成长奖励；不扫描历史核销补造奖励'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target='storeVerifyGrowthTask.run()');

-- runner: assert=scalar-true
SELECT COUNT(*)=1 AND SUM(cron_expression='0 * * * * ?' AND misfire_policy='3'
  AND concurrent='1' AND status='0')=1 AS assertion_ok
FROM sys_job WHERE invoke_target='storeVerifyGrowthTask.run()';
