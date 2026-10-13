-- P0 合规:报名 PII 历史明文加密回填(一次性运维任务)。《隐私政策》§6.3。
-- ★seed 时机:随 schema 迁移一批执行(jar 部署前),让部署重启的 @PostConstruct 把本 job(暂停态)装载进 Quartz,
--    否则 seed 在启动后、job 不在调度器里,UI 点「执行一次」会触发失败;若已在部署后才 seed,须再重启或 UI「修改→保存」热加载。
-- ★触发时机:必须在 ① chengyin.pii.key 已配 ② migration_registration_pii_encryption.sql 已落 ③ jar 部署 三者齐备后,
--    才在 RuoYi 定时任务里对本条点「执行一次」(回填需 bean 在位且加密已启用)。
-- 注册为 status='1'(暂停),避免按 cron 反复跑;由运维手动触发,跑到日志显示「累计加密 N 条…本批 0」为收敛。
-- 幂等:只挑 phone_hash 为空的历史行,可反复触发直至清零。
DELETE FROM sys_job WHERE invoke_target = 'piiBackfillTask.run()';
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_time)
VALUES ('报名PII历史加密回填(手动)', 'COMPLIANCE', 'piiBackfillTask.run()', '0 0 4 * * ?', '3', '1', '1', NOW());
