-- T+N 自动结算 定时任务注册(L3)
-- 走 chengyin-db-migrate skill:只读预检后确认再执行
-- 依赖:含 AutoSettlementTask 的新 jar 已部署
-- ⚠️ 该任务动真实资金(generateBatch + confirmBatch 把创作者/商家分成打到余额),
--    默认 status='1'(暂停);核对结算口径无误后,在后台「定时任务」启用。
-- cron 每日 02:30 一次;N(延迟天数)由 application 配 settlement.auto.delayDays(默认1)。

INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT 'T+N自动结算', 'DEFAULT', 'autoSettlementTask.run()', '0 30 2 * * ?', '3', '1', '1', 'admin', NOW(),
       'L3 待结算明细超T+N天自动生成批次并确认打款;默认暂停,核对后启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'autoSettlementTask.run()');
