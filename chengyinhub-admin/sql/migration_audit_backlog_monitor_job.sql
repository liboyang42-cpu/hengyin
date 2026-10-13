-- P0 合规:UGC 审核积压 SLA 监控定时任务(sys_job)。§3.4 先审后发 · HOLD 队列不能无人认领。
-- 待审(status=0)超 sla-hours 积压 / deadline 超期 → 超阈值告警(AlarmService → 钉钉/企微)。
-- ★执行顺序:随 schema 迁移一批执行(jar 部署前),让部署重启的 @PostConstruct 把本 job 装载进 Quartz 并按 cron 调度。
--    RuoYi 只在启动时从 sys_job 加载进 Quartz;若在部署后才 seed,本 job 不进调度(静默不跑),须再重启或在 UI 对本条「修改→保存」热加载。
--    (bean 在 jar 里,装载在部署重启时发生,那时 bean 已在,不会找不到。)
-- ⚠️ 阈值在 application.yml 用 chengyin.audit.sla-hours / backlog-threshold 配置。
-- 幂等:先删同 invoke_target 再插入,可重复执行。concurrent='1' 禁并发;每 30 分钟巡检。
DELETE FROM sys_job WHERE invoke_target = 'auditBacklogMonitorTask.run()';
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_time)
VALUES ('UGC审核积压监控', 'COMPLIANCE', 'auditBacklogMonitorTask.run()', '0 0/30 * * * ?', '3', '1', '0', NOW());
