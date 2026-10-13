-- D-02 提现出金状态巡检定时任务(sys_job)。
-- ★执行顺序:必须在 jar 部署(含 withdrawTransferQueryTask bean)之后执行,否则任务触发时找不到 bean。
-- 幂等:先删同 invoke_target 再插入,可重复执行。
-- concurrent='1' 禁止并发,避免同单重复处理;每 2 分钟巡检一次。
DELETE FROM sys_job WHERE invoke_target = 'withdrawTransferQueryTask.run()';
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_time)
VALUES ('提现出金状态巡检', 'FINANCE', 'withdrawTransferQueryTask.run()', '0 0/2 * * * ?', '3', '1', '0', NOW());
