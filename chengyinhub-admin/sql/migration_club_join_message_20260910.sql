-- 稿 P3 30:2 入会申请里申请人写的那句话。
-- 类别:③ 数据库迁移。合并前必须由总控走 chengyin-db-migrate,只读预检、用户确认、快照后手动落库。
-- 部署顺序:本 SQL 先落库,再合并/部署引用它的新 jar。
--
-- ★ 2026-09-10 本文件原来还带着 club 的两列(member_post_enabled / open_merchant_coop)。
--   落库前的只读预检查出:生产库**已经有**这三列了,名字不一样 ——
--     public_visible / merchant_undertake_open / member_post_allowed
--   是并行分支 [历史分支标识已移除] 的 migration_club_open_settings_20260909.sql 跑的
--   (那条分支至今没开 PR,代码没合,所以 master 上一个字都搜不到这三个名字)。
--   照原样跑下去就是同一个开关在库里有两列、两个真源。两列已删,本分支的代码改用生产库现有的列名。
--   ⚠️ 教训:「master 里搜不到」不等于「生产库里没有」。加列前先 describe 目标表。
--
-- ★ 可空、无默认值 = 与加这列之前的行为完全一致,存量行读到 NULL 按「没写留言」处理。
--
-- 执行前预检(第一条必须返回 0 行;第二条确认 AFTER 锚点存在):
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club_member' AND COLUMN_NAME = 'join_message';
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club_member' AND COLUMN_NAME = 'status';

ALTER TABLE club_member
  ADD COLUMN join_message VARCHAR(200) NULL DEFAULT NULL
  COMMENT '入会申请留言(申请人自己写的一句话,可空)'
  AFTER status;

-- 执行后回读(返回 1 行,nullable=YES、default=NULL):
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
-- FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club_member' AND COLUMN_NAME = 'join_message';
--
-- 回滚(先回滚 jar 再删列)。⚠️ join_message 是用户写的原文,删前先导出:
-- SELECT id, club_id, member_id, status, join_message FROM club_member WHERE join_message IS NOT NULL;
-- ALTER TABLE club_member DROP COLUMN join_message;
