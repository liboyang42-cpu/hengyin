-- POST_DEPLOY only(ci/migration_ledger.py POST_DEPLOY_MIGRATIONS):新 jar 已上线、健康、且含下面两个 bean 后才跑。
-- 不能先于新 jar 跑:SysJobServiceImpl.init 启动时、MySqlSchedulerCoordinator 每 2 秒都会校验全部 sys_job 行的
-- invoke_target bean(不论 status)。旧 jar 没有这两个 bean ⇒ 正在跑的旧 jar 会停掉全部定时任务,
-- 旧 jar 也无法在带这两行的库上启动(ROUND10 r1 F1/F2)。
--
-- 改什么:sys_job 新增两行,均注册为暂停(status='1'),新 jar 读回后由运维在后台启用:
--   contentSafetyRecheckTask.run()      内容安全自动复检(表见 migration_20261103_content_safety_recheck.sql)
--   sensitiveContentRescanTask.run()    敏感词存量复查(表见 migration_20261103_sensitive_content_library.sql)
-- 这两条 INSERT 原样从上面两个文件搬来(名称/cron/误火策略/并发/备注不变),那两个文件不再碰 sys_job。
-- 不改什么:已有任何 sys_job 行(含运维已启用/改过 cron 的这两行);重跑不插第二行。
--
-- 回滚到旧 jar 前必须先删这两行(否则旧 jar 起不来):
--   DELETE FROM sys_job WHERE invoke_target IN ('contentSafetyRecheckTask.run()', 'sensitiveContentRescanTask.run()');
-- 再次前滚到新 jar 后重跑本文件即可恢复(暂停态,需重新启用)。

-- 前置:两个 bean 的数据表已在(pre-deploy 两个文件已跑),且这两个目标没有重复行。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND engine = 'InnoDB'
          AND table_name IN ('content_safety_recheck','content_sensitive_word','content_sensitive_allow',
                             'content_sensitive_image','content_sensitive_hit')) = 5
  AND (SELECT COUNT(*) FROM sys_job WHERE invoke_target = 'contentSafetyRecheckTask.run()') <= 1
  AND (SELECT COUNT(*) FROM sys_job WHERE invoke_target = 'sensitiveContentRescanTask.run()') <= 1
  AS content_safety_jobs_preconditions;

INSERT INTO sys_job
  (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
   create_by, create_time, remark)
SELECT '内容安全自动复检', 'DEFAULT', 'contentSafetyRecheckTask.run()', '21 1/5 * * * ?',
       '3', '1', '1', 'admin', NOW(),
       '每 5 分钟一轮，每轮最多 50 条；通过自动发布，违规/耗尽自动驳回并通知作者'
WHERE NOT EXISTS (
  SELECT 1 FROM sys_job WHERE invoke_target = 'contentSafetyRecheckTask.run()'
);

INSERT INTO sys_job
  (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
   create_by, create_time, remark)
SELECT '敏感词存量复查', 'DEFAULT', 'sensitiveContentRescanTask.run()', '33 2/10 * * * ?',
       '3', '1', '1', 'admin', NOW(),
       '词库变化后复查公开文本;每轮最多 500 条;默认只报告(content.sensitive.rescan.remove)'
WHERE NOT EXISTS (
  SELECT 1 FROM sys_job WHERE invoke_target = 'sensitiveContentRescanTask.run()'
);

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_job WHERE invoke_target = 'contentSafetyRecheckTask.run()') = 1
  AND (SELECT COUNT(*) FROM sys_job WHERE invoke_target = 'sensitiveContentRescanTask.run()') = 1
  AS content_safety_jobs_registered;
