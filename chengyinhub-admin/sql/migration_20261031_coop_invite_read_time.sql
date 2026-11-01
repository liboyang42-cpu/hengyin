-- 合作邀约「已读」回执 coop_invite.read_time(2026-10-31 业主批准 P-6:已邀约一行三格状态 已发出 → 已读 → 已接受)
-- 执行方式:走 chengyin-db-migrate,只读预检后确认。部署新 jar 前先落库(新 jar 读写 read_time)。
-- 语义:受邀方第一次打开这条邀约详情时写一次,之后不再覆盖;NULL = 还没读。
-- 可重复执行:加列先查 information_schema。不回填 —— 存量邀约读没读过不可知,如实留空。

SET @coop_invite_read_time_exists := (
    SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='coop_invite' AND column_name='read_time');
SET @coop_invite_read_time_sql := IF(@coop_invite_read_time_exists=0,
    'ALTER TABLE coop_invite ADD COLUMN read_time DATETIME NULL COMMENT ''受邀方首次打开时间(已读回执)'' AFTER handle_time',
    'SELECT 1');
PREPARE coop_invite_read_time_stmt FROM @coop_invite_read_time_sql;
EXECUTE coop_invite_read_time_stmt;
DEALLOCATE PREPARE coop_invite_read_time_stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='coop_invite' AND column_name='read_time')=1 AS coop_invite_read_time_ready;

-- 回退:保留列(旧 jar 不读它)。禁止 DROP —— 会丢掉已读回执。
