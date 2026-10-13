-- R10-04 重新落库:竞猜轮次超时作废任务(predictVoidExpiredTask)的 Quartz 注册。
--
-- 病历(两段,都不是「漏了注销」):
--   · 2026-09-06 注册(#128)跑在代码前面 —— 当时生产 jar 里没有这个 bean,
--     2026-09-10 / 09-11 连续两次 03:00 执行都是 NoSuchBeanDefinitionException。
--   · 2026-09-11 的 migration_predict_void_expired_job_decommission_20260911.sql
--     把这条注册摘掉了,并写明「代码到位后重新落库」。
--   · 2026-09-13 第十轮对账:生产 jar(SHA 7860bfa)已含 PredictVoidExpiredTask.class,
--     但 sys_job 仍无 predictVoidExpiredTask 注册 → 商家超期不给答案时,48 小时作废
--     兜底没有自动触发路径,而且不报错、零告警(不炸所以发现不了)。
--
-- ⚠️ 本次只落盘,不在任何库执行;由总控按迁移流程(只读预检 + 确认)后运行。
-- ⚠️ 前置:含 PredictVoidExpiredTask.class 的 jar 已部署(2026-09-13 生产进程已实证)。
-- ⚠️ 落库后要回读 sys_job(注册行 + status='0')与 sys_job_log(下一轮 03:00 的成功记录),
--    不能只看 INSERT 回执 —— 注册了但调度器没装载,同样是静默缺失。
--
-- 取值依据(与 20260911 注销前的运行态一致,并抄 20260906 那份注册的口径):
--   · cron '0 0 3 * * ?' —— 作废判据是「过了截止 48 小时」,每天扫一次足够;
--     挑凌晨 03:00 是避开白天商家结算与玩家下注高峰。
--   · misfire_policy '3'(MISFIRE_DO_NOTHING,见 ScheduleConstants)——
--     错过就跳过、等第二天的 cron。它是自收敛任务,补跑一天没有业务价值,
--     而在容器重启后立即补跑反而会平白扫一轮全量。
--   · concurrent '1'(禁止并发,见 SysJob.concurrent 注释)——
--     同一时刻只允许一次扫描,避免两轮同时改 play_predict_bet.reward_status;
--     每天一次的任务本来也没有并发重叠的必要。
--   · status '0'(启用)且不注册成暂停:任务自收敛,没有过期轮次时空跑返回 0,
--     不该给未来的自己派一个「记得来点启动」的活(注册即暂停=没有到期日的活)。
--
-- 幂等:invoke_target 已存在则跳过,不删任何历史行。
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '竞猜轮次超时作废', 'DEFAULT', 'predictVoidExpiredTask.run()', '0 0 3 * * ?', '3', '1', '0', 'system', NOW(),
       '商家超过48小时不给竞猜答案的轮次作废且不发奖;自收敛任务,无过期轮次时空跑'
FROM dual
WHERE NOT EXISTS (
  SELECT 1 FROM (SELECT job_id FROM sys_job WHERE invoke_target = 'predictVoidExpiredTask.run()' LIMIT 1) existing
);

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AND SUM(cron_expression = '0 0 3 * * ?' AND misfire_policy = '3'
       AND concurrent = '1' AND status = '0') = 1 AS assertion_ok
FROM sys_job WHERE invoke_target = 'predictVoidExpiredTask.run()';
