-- ================= migration_zzz_committed_official_notice_job_reregister_20261111.sql =================
-- f-rehearsal 修正(2026-11-11):只为「jar 回滚后再前滚」补登记 committedOfficialNoticeTask 的暂停任务。
--
-- 原因:DEPLOY.md 第 12 节规定回滚到旧 jar 前先删掉旧 jar 没有 bean 的 sys_job 行,再前滚时「重跑对应迁移即可恢复」。
--   committedOfficialNoticeTask.deliverPending() 的任务行由 migration_20261024_d_committed_official_notice_wx.sql 插入,
--   但那个文件先 CREATE TABLE(不带 IF NOT EXISTS),是单次执行项(APPLIED 时必须跳过),重跑在建表处报 1050 停下,
--   任务行不会回来。f-rehearsal 在 MySQL 8.0 上演练回滚时实测到这一点。
--   原文件生产可能已执行,不改它;本文件只把同一条任务行按 invoke_target 判重再登记一次。
--
-- 正常发布路径上本文件是空操作(原文件已登记该行,WHERE NOT EXISTS 命中 0 行)。可重复执行。
-- 任务保持暂停(status='1'),已存在的行(含人工启用过的)原样保留。
-- 只插 sys_job:必须在包含 committedOfficialNoticeTask bean 的新 jar 健康之后执行(同 DEPLOY 第 3_POST_DEPLOY 轮)。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认。
--
-- 落库前只读预检:
--   SELECT job_id, invoke_target, status FROM sys_job WHERE invoke_target = 'committedOfficialNoticeTask.deliverPending()';

-- 前置:原迁移的表已在(否则 bean 读写的表不存在,登记任务没有意义)。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx';

INSERT INTO sys_job
    (job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '原业务官方通知微信恢复','DEFAULT','committedOfficialNoticeTask.deliverPending()',
       '0 0/1 * * * ?','3','1','1','system',NOW(),
       '默认暂停；仅领取PENDING原意图；UNKNOWN保留原号人工核实，禁止自动重发'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target='committedOfficialNoticeTask.deliverPending()'
);

-- 回读断言:该任务恰好一行。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM sys_job WHERE invoke_target = 'committedOfficialNoticeTask.deliverPending()';
