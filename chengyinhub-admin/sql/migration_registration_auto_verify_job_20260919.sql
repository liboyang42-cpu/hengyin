-- 阶段:post-deploy —— 必须先部署含 RegistrationAutoVerifyTask.run() 的新 jar 再注册本任务,
-- 否则 Quartz 每轮反序列化都抛 NoSuchMethodException(predictVoidExpiredTask 的老教训)。
-- ⚠️ 任务没注册 = jar 部署了也永不调度,且不报错零告警(见 ci/migration_gates.py 病历)。
--
-- RUN-01 / 拍板纪要#5:过期未核销场次票到期自动核销关闭、不退款。
-- 扫描口径(cms_registration + cms_activity.end_date + 7 天安全缓冲,退款在途/缺锚点不扫),
-- 逐单条件 UPDATE 当前读收口 —— 商家真扫码/用户提交退款/活动改期均会让本任务 0 行跳过。
-- 只收口 owner_type=2 活动单;主题票结算写入在核销动作里,不经本任务。
-- 幂等:INSERT ... WHERE NOT EXISTS,重跑不插第二行、不换 job_id。
-- 依赖:含本任务的 jar 已部署;无其它迁移依赖。
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression,
                     misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '到期未核销场次票自动核销', 'DEFAULT', 'registrationAutoVerifyTask.run()', '0 30 * * * ?',
       '3', '1', '0', 'system', NOW(),
       'RUN-01 拍板#5:活动结束满7天仍已支付未核销的场次票自动置已核销收口(不退款;退款在途/缺锚点不扫)'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target = 'registrationAutoVerifyTask.run()'
);

-- runner: assert=scalar-true
SELECT COUNT(*)=1 AND SUM(cron_expression='0 30 * * * ?' AND misfire_policy='3'
  AND concurrent='1' AND status='0')=1 AS assertion_ok
FROM sys_job WHERE invoke_target='registrationAutoVerifyTask.run()';
