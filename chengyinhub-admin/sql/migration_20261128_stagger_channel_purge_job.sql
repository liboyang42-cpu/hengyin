-- 定时任务错峰补充(2026-11-28,W7 x-channel-safety):给 20261128_channel_purge_job 登记的 sys_job 分配未用的秒位。
-- 背景:channelPurgeTask.purge(50) 登记为 '0 40 3 * * ?'(落在 0 秒)。
--       SysJobStaggerPlanTest 要求每个任务独占一个秒位,并把本文件的计划行并入全量计划后逐秒模拟 24 小时。
-- 做法:与 20261116_stagger_channel_membership_job 同一口径 —— 频率不变,只改秒位;秒位 46 在全量计划里未被占用。
--       只改仍是登记默认值的行(人工改过的表达式不覆盖),可重复执行。
-- 生效:应用启动时 SysJobServiceImpl.init 按 sys_job 重建调度;行不存在时 UPDATE 影响 0 行,不报错。
-- 回退:把 SET 的新值换回 IN (...) 中原来的值即可(只动 cron_expression)。

-- 计划表(SysJobStaggerPlanTest 解析这些行):
-- plan: channelPurgeTask.purge(50) = 46 40 3 * * ?

UPDATE sys_job SET cron_expression = '46 40 3 * * ?', update_time = NOW() WHERE invoke_target = 'channelPurgeTask.purge(50)' AND cron_expression IN ('0 40 3 * * ?');
