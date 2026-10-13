-- P0 合规:数据留存清理定时任务(sys_job)。《隐私政策》§6.4 数据留存条款。
-- 清理 tms_browse_record(含IP/UA,默认保留180天)+ biz_event_log(默认保留365天),分批删除不锁表。
-- ★执行顺序:随 schema 迁移一批执行(jar 部署前),让部署重启的 @PostConstruct 把本 job 装载进 Quartz 并按 cron 调度。
--    RuoYi 只在启动时从 sys_job 加载进 Quartz;若在部署后才 seed,本 job 不进调度(静默不跑),须再重启或在 UI 对本条「修改→保存」热加载。
--    (bean 在 jar 里,装载在部署重启时发生,那时 bean 已在,不会找不到。)
-- ⚠️ 保留天数在 application.yml 用 chengyin.retention.browse-days/event-days 配置,须与线上《隐私政策》承诺一致。
-- 幂等:先删同 invoke_target 再插入,可重复执行。
-- concurrent='1' 禁止并发;每日 03:30 低峰跑一次。
DELETE FROM sys_job WHERE invoke_target = 'dataRetentionCleanupTask.run()';
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_time)
VALUES ('数据留存清理', 'COMPLIANCE', 'dataRetentionCleanupTask.run()', '0 30 3 * * ?', '3', '1', '0', NOW());
