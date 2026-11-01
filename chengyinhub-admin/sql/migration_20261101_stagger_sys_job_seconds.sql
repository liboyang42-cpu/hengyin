-- 定时任务错峰(2026-11-01 上线前生产走查)
-- 现象:每 5 分钟整点约 14 个任务同秒起跑,Druid 20 连接被占满,D12 采样 active≈12 waiting 5~8,每天约 100 次「数据库连接池等待」告警;
--       同一秒进来的用户请求排队到 maxWait(5s)失败(首页三个列表在 15:05 同时读失败)。
-- 做法:只改秒位与分钟偏移,频率不变;D12 指标采样(121)留在 0 秒,正好量到错峰后的真实占用。
--       出发前提醒(102)仍在整点后 15 秒,分润结算(105)挪到每小时第 14 分(避开整点),其余按频率错开。
-- 生效:应用启动时 SysJobServiceImpl.init 按 sys_job 重建调度 —— 本迁移须在部署/重启前执行。
-- 只改仍是原表达式的行(人工改过的不覆盖),可重复执行。

UPDATE sys_job SET cron_expression = '7 * * * * ?', update_time = NOW() WHERE job_id = 140 AND cron_expression = '0 * * * * ?';
UPDATE sys_job SET cron_expression = '13 * * * * ?', update_time = NOW() WHERE job_id = 135 AND cron_expression = '0 * * * * ?';
UPDATE sys_job SET cron_expression = '19 * * * * ?', update_time = NOW() WHERE job_id = 130 AND cron_expression = '0 * * * * ?';
UPDATE sys_job SET cron_expression = '25 * * * * ?', update_time = NOW() WHERE job_id = 127 AND cron_expression = '0 * * * * ?';
UPDATE sys_job SET cron_expression = '31 * * * * ?', update_time = NOW() WHERE job_id = 126 AND cron_expression = '0 * * * * ?';
UPDATE sys_job SET cron_expression = '37 * * * * ?', update_time = NOW() WHERE job_id = 125 AND cron_expression = '0 * * * * ?';
UPDATE sys_job SET cron_expression = '43 1/2 * * * ?', update_time = NOW() WHERE job_id = 116 AND cron_expression = '0 0/2 * * * ?';
UPDATE sys_job SET cron_expression = '3 0/5 * * * ?', update_time = NOW() WHERE job_id = 109 AND cron_expression = '0 0/5 * * * ?';
UPDATE sys_job SET cron_expression = '49 1/5 * * * ?', update_time = NOW() WHERE job_id = 110 AND cron_expression = '0 0/5 * * * ?';
UPDATE sys_job SET cron_expression = '55 2/5 * * * ?', update_time = NOW() WHERE job_id = 139 AND cron_expression = '0 0/5 * * * ?';
UPDATE sys_job SET cron_expression = '11 3/10 * * * ?', update_time = NOW() WHERE job_id = 119 AND cron_expression = '0 0/10 * * * ?';
UPDATE sys_job SET cron_expression = '17 4/10 * * * ?', update_time = NOW() WHERE job_id = 106 AND cron_expression = '0 0/10 * * * ?';
UPDATE sys_job SET cron_expression = '23 6/10 * * * ?', update_time = NOW() WHERE job_id = 133 AND cron_expression = '0 0/10 * * * ?';
UPDATE sys_job SET cron_expression = '29 7/10 * * * ?', update_time = NOW() WHERE job_id = 124 AND cron_expression = '0 0/10 * * * ?';
UPDATE sys_job SET cron_expression = '35 8/10 * * * ?', update_time = NOW() WHERE job_id = 132 AND cron_expression = '0 0/10 * * * ?';
UPDATE sys_job SET cron_expression = '41 9/10 * * * ?', update_time = NOW() WHERE job_id = 107 AND cron_expression = '0 0/10 * * * ?';
UPDATE sys_job SET cron_expression = '47 11/15 * * * ?', update_time = NOW() WHERE job_id = 118 AND cron_expression = '0 0/15 * * * ?';
UPDATE sys_job SET cron_expression = '53 12/30 * * * ?', update_time = NOW() WHERE job_id = 113 AND cron_expression = '0 0/30 * * * ?';
UPDATE sys_job SET cron_expression = '59 13/30 * * * ?', update_time = NOW() WHERE job_id = 129 AND cron_expression = '0 0/30 * * * ?';
UPDATE sys_job SET cron_expression = '5 14 * * * ?', update_time = NOW() WHERE job_id = 105 AND cron_expression = '0 0 * * * ?';
UPDATE sys_job SET cron_expression = '15 0 * * * ?', update_time = NOW() WHERE job_id = 102 AND cron_expression = '0 0 * * * ?';
UPDATE sys_job SET cron_expression = '30 5 3 * * ?', update_time = NOW() WHERE job_id = 131 AND cron_expression = '0 0 3 * * ?';
UPDATE sys_job SET cron_expression = '30 5 4 * * ?', update_time = NOW() WHERE job_id = 117 AND cron_expression = '0 0 4 * * ?';

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_job WHERE status = '0' AND cron_expression IN ('0 * * * * ?', '0 0/2 * * * ?', '0 0/10 * * * ?', '0 0/15 * * * ?', '0 0/30 * * * ?')) = 0
   AND (SELECT COUNT(*) FROM sys_job WHERE status = '0' AND cron_expression = '0 0/5 * * * ?') <= 1 AS sys_job_staggered;

-- 回退:把上面每行的新旧表达式对调执行即可(只动 cron_expression)。
