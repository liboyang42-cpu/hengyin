-- F0384 / M0117(2026-10-12):漫游足迹分享快照加版本号,支持逐条撤回与版本条件更新。
-- 语义:
--   version 从 1 起;同一条足迹(member_id + client_ts)每次重新发布 +1。
--   客户端带 expectedVersion 发布时按 version 条件更新,不一致 = 版本冲突(并发改图),不覆盖。
--   逐条撤回 = 按 member_id + share_token 删行(只删本人);匿名读取删行后即读不到。
-- 存量行默认 version=1,无需回填。
-- 幂等守卫:version 为哨兵列,已存在则整段跳过(MySQL 8 无 ADD COLUMN IF NOT EXISTS)。
-- ⚠️ 部署顺序:必须先落本迁移再上新 jar(新 mapper 读写 version 列)。
-- ⚠️ 只在库上跑:合入 master 前须走 chengyin-db-migrate 只读预检 + 用户确认后落库;本文件不自动执行。

SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot'
                 AND column_name = 'version');
SET @sql := IF(@exist = 0,
  'ALTER TABLE roam_share_snapshot
     ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT ''快照版本;同一足迹每次重新发布+1'' AFTER snapshot_json',
  'SELECT ''roam_share_snapshot.version 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'roam_share_snapshot'
  AND column_name = 'version'
  AND data_type = 'int'
  AND is_nullable = 'NO';

-- 回滚(确认客户端不再依赖版本号后):
-- ALTER TABLE roam_share_snapshot DROP COLUMN version;
