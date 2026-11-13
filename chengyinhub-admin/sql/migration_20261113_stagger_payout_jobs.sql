-- 收付通定时任务错峰(2026-11-13 发布迁移演练冲突修复)
-- 现象:20261112_dec_c_payout_jobs 启用 ecommercePayoutTask.shareAndRetry() / syncOnboarding(),
--       二者仍是 20261110_ecommerce_payout_wiring 写入的整点类表达式,违反 20261101_stagger_sys_job_seconds 的末态断言
--       (不允许 status='0' 且 cron 为 '0 0/10 * * * ?' 等整点表达式),全集应用后该项预检回到 NEEDED。
-- 做法:沿用 20261101_stagger_sys_job_seconds 的约定,只改秒位与分钟偏移,频率不变;
--       秒位 9 / 57 及分钟偏移 5/10、18/30 均未被 11-01 错峰任务占用。
--       committedOfficialNoticeTask.deliverPending()(每分钟,0 秒)一并挪到 27 秒。
-- 不改任何已有迁移;只改仍是原表达式的行(人工改过的不覆盖),可重复执行。
-- 生效:应用启动时 SysJobServiceImpl.init 按 sys_job 重建调度 —— 须在 dec_c_payout_jobs 之后(POST_DEPLOY)执行。

UPDATE sys_job SET cron_expression = '9 5/10 * * * ?', update_time = NOW() WHERE invoke_target = 'ecommercePayoutTask.shareAndRetry()' AND cron_expression = '0 0/10 * * * ?';
UPDATE sys_job SET cron_expression = '57 18/30 * * * ?', update_time = NOW() WHERE invoke_target = 'ecommercePayoutTask.syncOnboarding()' AND cron_expression = '0 15/30 * * * ?';
UPDATE sys_job SET cron_expression = '27 0/1 * * * ?', update_time = NOW() WHERE invoke_target = 'committedOfficialNoticeTask.deliverPending()' AND cron_expression = '0 0/1 * * * ?';

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_job WHERE invoke_target IN ('ecommercePayoutTask.shareAndRetry()', 'ecommercePayoutTask.syncOnboarding()', 'committedOfficialNoticeTask.deliverPending()')
          AND cron_expression IN ('0 0/10 * * * ?', '0 15/30 * * * ?', '0 0/1 * * * ?')) = 0
   AND (SELECT COUNT(*) FROM sys_job WHERE status = '0' AND cron_expression IN ('0 * * * * ?', '0 0/2 * * * ?', '0 0/10 * * * ?', '0 0/15 * * * ?', '0 0/30 * * * ?')) = 0 AS payout_jobs_staggered;

-- 回退:把上面每行的新旧表达式对调执行即可(只动 cron_expression)。
