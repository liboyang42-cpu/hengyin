-- 定时任务错峰补充(2026-11-15,路线图集成):给 20261115_stagger_all_sys_jobs 之后新登记的两个 sys_job 分配未用的秒位。
-- 背景:20261115_club_reach 登记 clubChannelDefaultsTask.backfill(200)('0 0 4 * * ?',与 metricsProbeTask 的 04:00:00 同秒);
--       20261115_growth_loops 登记 growthWinbackTask.run()('0 7 * * * ?',落在 0 秒)。
--       SysJobStaggerPlanTest 要求每个任务独占一个秒位,并把本文件的计划行并入全量计划后逐秒模拟 24 小时。
-- 做法:与 20261115_stagger_all_sys_jobs 同一口径 —— 频率不变,只改秒位;秒位 36 / 38 在全量计划里未被占用。
--       只改仍是登记默认值的行(人工改过的表达式不覆盖),可重复执行。
-- 生效:应用启动时 SysJobServiceImpl.init 按 sys_job 重建调度;两行不存在时 UPDATE 影响 0 行,不报错。
-- 回退:把每行 SET 的新值换回 IN (...) 中原来的值即可(只动 cron_expression)。

-- 计划表(SysJobStaggerPlanTest 解析这些行):
-- plan: clubChannelDefaultsTask.backfill(200) = 36 0 4 * * ?
-- plan: growthWinbackTask.run() = 38 7 * * * ?

UPDATE sys_job SET cron_expression = '36 0 4 * * ?', update_time = NOW() WHERE invoke_target = 'clubChannelDefaultsTask.backfill(200)' AND cron_expression IN ('0 0 4 * * ?');
UPDATE sys_job SET cron_expression = '38 7 * * * ?', update_time = NOW() WHERE invoke_target = 'growthWinbackTask.run()' AND cron_expression IN ('0 7 * * * ?');
