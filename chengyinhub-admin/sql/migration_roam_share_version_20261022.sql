-- Lane W10b #23(2026-10-22):漫游足迹分享快照加版本号,发布走 version CAS,支持按令牌撤销单条。
-- 前置:migration_20260917_roam_share_snapshot.sql(建表)。幂等:列已存在则跳过。
-- 旧行回填为 1;之后每次同一条足迹重复发布 version + 1。客户端带 expectedVersion 发布时,
-- 版本不符(含已被撤销)即拒绝,避免旧页面把已撤销/已更新的分享静默写回。
-- 必须先于新 jar 落库:新 jar 的发布 SQL 会写 version 列。按 chengyin-db-migrate 流程只读预检 + 用户确认后执行。
-- 回滚(确认无客户端依赖版本号后):ALTER TABLE roam_share_snapshot DROP COLUMN version;
SET @roam_share_version_column = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND column_name = 'version');
SET @roam_share_version_ddl = IF(@roam_share_version_column = 0,
  'ALTER TABLE roam_share_snapshot ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT ''同一条足迹的发布版本,发布 CAS 用''', 'SELECT 1');
PREPARE roam_share_version_statement FROM @roam_share_version_ddl;
EXECUTE roam_share_version_statement;
DEALLOCATE PREPARE roam_share_version_statement;
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.columns WHERE table_schema = DATABASE()
  AND table_name = 'roam_share_snapshot' AND column_name = 'version' AND data_type = 'int'
  AND is_nullable = 'NO' AND column_default = '1';
