-- ================= migration_20261115_club_reach.sql =================
-- club-reach (Q2 roadmap): default club channels 闲聊 / 公告 / 活动 and their backfill job.
--
-- ROLLBACK: DELETE FROM sys_job WHERE invoke_target = 'clubChannelDefaultsTask.backfill(200)';
--   Channels already seeded by the job are ordinary channel rows: the owner can archive or delete them in the app.
--   Do not bulk-delete im_channel rows; members may already have written messages in them.
--
-- No table or column changes. New clubs are seeded in code (club create and admin approval);
-- the channel directory still provisions on first open. This file only registers the one-off backfill
-- for open clubs that have no channel row at all. Idempotent (WHERE NOT EXISTS). The job stays paused
-- (status='1'); ops runs it once from 系统监控 → 定时任务 after the new jar is healthy, then may leave it paused.
-- It only needs the clubChannelDefaultsTask bean, so run it in the post-deploy round.
--
-- Read-only precheck (how many clubs the backfill would touch):
--   SELECT COUNT(*) FROM club c WHERE c.status = 1 AND c.del_flag = 0 AND c.member_id IS NOT NULL
--      AND NOT EXISTS (SELECT 1 FROM im_channel ch WHERE ch.guild_kind = 1 AND ch.guild_id = c.id);

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name = 'im_channel';

INSERT INTO sys_job
    (job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '俱乐部默认频道补齐','DEFAULT','clubChannelDefaultsTask.backfill(200)',
       '0 0 4 * * ?','3','1','1','system',NOW(),
       '默认暂停；为没有任何频道的在营俱乐部补 闲聊/公告/活动，每次最多 200 个，可重复执行'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target='clubChannelDefaultsTask.backfill(200)'
);

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM sys_job WHERE invoke_target = 'clubChannelDefaultsTask.backfill(200)';
