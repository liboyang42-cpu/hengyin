-- 定时任务错峰补充(2026-11-16,W7 x-city-monopoly CITY-6):给 20261127_city_monopoly_jobs 登记的三个 sys_job 分配未用的秒位。
-- 做法:与 20261115_stagger_all_sys_jobs 同一口径;秒位 44 / 50 / 56 在全量计划里未被占用。
--       登记文件已直接用这些秒位;这里的 UPDATE 只纠正仍是整分(0 秒)写法的行,可重复执行。
-- 回退:把每行 SET 的新值换回 IN (...) 中原来的值即可(只动 cron_expression)。

-- 计划表(SysJobStaggerPlanTest 解析这些行):
-- plan: cityMonopolyTask.settle() = 44 3 3 * * ?
-- plan: cityMonopolyTask.nightly() = 50 23 3 * * ?
-- plan: cityMonopolyTask.seasonSettle() = 56 7 9 * * ?

UPDATE sys_job SET cron_expression = '44 3 3 * * ?', update_time = NOW() WHERE invoke_target = 'cityMonopolyTask.settle()' AND cron_expression IN ('0 3 3 * * ?');
UPDATE sys_job SET cron_expression = '50 23 3 * * ?', update_time = NOW() WHERE invoke_target = 'cityMonopolyTask.nightly()' AND cron_expression IN ('0 23 3 * * ?');
UPDATE sys_job SET cron_expression = '56 7 9 * * ?', update_time = NOW() WHERE invoke_target = 'cityMonopolyTask.seasonSettle()' AND cron_expression IN ('0 7 9 * * ?');
