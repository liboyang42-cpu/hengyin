-- 俱乐部活动运营 Quartz 注册。
-- 阶段：post-deploy；必须在 migration_club_role_event_ops_20260823.sql 结构迁移成功，
-- 且包含 clubEventOpsTask 的新 jar 已部署并完成 commit 读回后执行。
-- 本文件只注册任务，不包含 schema DDL。concurrent='1' 表示禁止并发，status='0' 表示启用。

INSERT INTO sys_job
  (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
   create_by, create_time, remark)
SELECT '俱乐部活动运营维护', 'DEFAULT', 'clubEventOpsTask.run()', '0 * * * * ?',
       '3', '1', '0', 'admin', NOW(),
       '每分钟维护候补到期/报名终态/晋级与站内通知失败项；CAS有界批处理'
WHERE NOT EXISTS (
  SELECT 1 FROM sys_job WHERE invoke_target = 'clubEventOpsTask.run()'
);

-- exact-one 配置读回；同 invoke_target 存在重复行或错误配置都必须红。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  AND SUM(job_name = '俱乐部活动运营维护' AND job_group = 'DEFAULT'
    AND cron_expression = '0 * * * * ?' AND misfire_policy = '3'
    AND concurrent = '1' AND status = '0') = 1 AS assertion_ok
FROM sys_job
WHERE invoke_target = 'clubEventOpsTask.run()';
