-- ================= migration_20261116_channel_membership_backfill.sql =================
-- W7 x-channel-reach (SOC-2): enrol existing guild members into the channels they can see.
--
-- ROLLBACK: DELETE FROM sys_job WHERE invoke_target = 'channelMembershipBackfillTask.backfill(200)';
--   Rows the job added are ordinary im_conversation_member rows (unread 0, read up to the latest message).
--   Do not bulk-delete them: members may have read or muted those channels since.
--
-- No table or column changes. New joins and new channels enrol in code (club join/approval, team join,
-- channel create, permission overwrite change). This file only registers the one-off backfill for members
-- who joined before this release and never opened a channel. Idempotent (INSERT IGNORE; WHERE NOT EXISTS).
-- The job stays paused (status='1'); ops runs it once from 系统监控 → 定时任务 after the new jar is healthy.
-- It names the channelMembershipBackfillTask bean only the new jar has, so it runs in the post-deploy round.
--
-- Read-only precheck (live text channels the backfill will walk):
--   SELECT COUNT(*) FROM im_channel WHERE del_flag = 0 AND status = 0 AND kind <> 0;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name = 'im_channel';

INSERT INTO sys_job
    (job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '频道成员补齐','DEFAULT','channelMembershipBackfillTask.backfill(200)',
       '0 20 4 * * ?','3','1','1','system',NOW(),
       '默认暂停；把俱乐部/队伍在册成员补进他们看得见的频道会话（只补缺的行，不改已有行），每页 200 个频道，可重复执行'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target='channelMembershipBackfillTask.backfill(200)'
);

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM sys_job WHERE invoke_target = 'channelMembershipBackfillTask.backfill(200)';
