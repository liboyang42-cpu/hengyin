-- 玩家队伍超时结束任务：依赖 playTeamExpireTask bean；只结束队伍和关闭群聊，绝不退款。
-- 每 10 分钟扫描一次；concurrent='1' 禁止同一任务并发，status='0' 注册即启用。
INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
     create_by, create_time, remark)
SELECT '玩家队伍超时结束', 'DEFAULT', 'playTeamExpireTask.run()', '0 0/10 * * * ?',
       '3', '1', '0', 'admin', NOW(),
       '逐team幂等结束已到场次开始时间的邀请制队伍并关闭群聊；不读取订单、不触发退款'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target = 'playTeamExpireTask.run()'
);
