-- ================= migration_20261128_channel_purge_job.sql =================
-- W7 x-channel-safety (SOC-14): purge channels deleted more than the restore window (7 days) ago.
--
-- ROLLBACK: DELETE FROM sys_job WHERE invoke_target = 'channelPurgeTask.purge(50)';
--   Purged channels (status 4) cannot be restored; the job only touches channels already past purge_at.
--
-- No table or column changes. Registers the purge job paused (status='1'); ops enables it from
-- 系统监控 → 定时任务 after the new jar is healthy. It names the channelPurgeTask bean only the new jar has,
-- so it runs in the post-deploy round. The job runs on the scheduling leader only. Idempotent (WHERE NOT EXISTS).
--
-- Read-only precheck (deleted channels already past the restore window):
--   SELECT COUNT(*) FROM im_channel WHERE status = 3 AND del_flag = 0 AND purge_at < NOW();

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name = 'im_channel';

INSERT INTO sys_job
    (job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '已删除频道清理','DEFAULT','channelPurgeTask.purge(50)',
       '0 40 3 * * ?','3','1','1','system',NOW(),
       '默认暂停；删除超过 7 天恢复期的频道：删除其消息及表情、提及、话题、修订、置顶与变更记录，频道置为已清理（4），每次最多 50 个频道'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target='channelPurgeTask.purge(50)'
);

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM sys_job WHERE invoke_target = 'channelPurgeTask.purge(50)';
