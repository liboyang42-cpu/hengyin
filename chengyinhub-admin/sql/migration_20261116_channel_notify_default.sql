-- ================= migration_20261116_channel_notify_default.sql =================
-- W7 x-channel-reach (SOC-2): per-channel default notification level.
--
-- ROLLBACK: keep the column (the old jar never reads it). To undo only the defaults:
--   UPDATE im_channel SET notify_default = NULL;
--   Do not DROP the column while the new jar runs: it reads it for every channel push.
--
-- im_channel.notify_default: NULL = inherit (account default → club default → all); 0 = all messages;
-- 1 = mentions only; 2 = nothing. A member's own setting for the channel or account still wins.
-- Owner decision (SOC-2): 公告 (kind 2) and the 活动 channel seeded by provisioning default to "all messages",
-- so announcements and new events reach every member who has not changed the setting.
-- Idempotent: the ALTER is guarded by information_schema; the UPDATEs only touch rows still NULL.
-- Runs before the new jar (the jar reads the column; a read failure only falls back to the old wording).

SET @ch_notify_default_exists := (
    SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='im_channel' AND column_name='notify_default');
SET @ch_notify_default_sql := IF(@ch_notify_default_exists=0,
    'ALTER TABLE im_channel ADD COLUMN notify_default TINYINT NULL DEFAULT NULL COMMENT ''频道默认通知等级 NULL继承 0全部 1仅@ 2不推'' AFTER slowmode_sec',
    'SELECT 1');
PREPARE ch_notify_default_stmt FROM @ch_notify_default_sql;
EXECUTE ch_notify_default_stmt;
DEALLOCATE PREPARE ch_notify_default_stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='im_channel' AND column_name='notify_default')=1 AS ch_notify_default_ready;

-- 公告 channels (kind 2): all messages by default.
UPDATE im_channel SET notify_default = 0
 WHERE kind = 2 AND notify_default IS NULL AND del_flag = 0;

-- The 活动 text channel that club provisioning created (CREATE op-log line with reason 'provision').
UPDATE im_channel ch
  JOIN im_channel_op_log l ON l.channel_id = ch.id AND l.action = 'CREATE' AND l.reason = 'provision'
   SET ch.notify_default = 0
 WHERE ch.guild_kind = 1 AND ch.kind = 1 AND ch.name = '活动' AND ch.notify_default IS NULL AND ch.del_flag = 0;
