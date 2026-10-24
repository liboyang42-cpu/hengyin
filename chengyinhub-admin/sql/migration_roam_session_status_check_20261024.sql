-- DEFER-ROAM(2026-10-24):roam_session.status 两态加库侧 CHECK。
-- 背景:status 只有 0(进行中)/1(已结算)两态,写入只走 0→1 的 CAS(RoamMapper.updateSessionFinish
--   ... and status = 0);召回扫描索引 idx_roam_session_recall 以 status 为首列、只扫 status = 1。
--   此前两态只写在列注释里,库侧没有约束;Java 侧常量见 RoamSession.STATUS_ACTIVE / STATUS_FINISHED。
-- 只加约束,不改列定义、不改数据、不碰索引。NULL 按 MySQL CHECK 语义放行(列本身允许 NULL,本迁移不收紧)。
-- 幂等:information_schema 预检 + PREPARE,约束已存在则跳过,可重复执行。
-- 若库里已有 0/1 以外的取值,ADD CONSTRAINT 会以 3819 失败、不做任何改动 —— 先人工核对那些行,不要绕过。
-- 预检(只读):SELECT status, COUNT(*) FROM roam_session GROUP BY status;
-- ⚠️ MySQL 8 加 CHECK 走 COPY 重建表;生产执行前走 chengyin-db-migrate 只读预检 + owner 确认,本 PR 不在任何库上执行。
-- 回滚:ALTER TABLE roam_session DROP CHECK ck_roam_session_status;
SET @roam_session_status_check_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'roam_session'
      AND constraint_name = 'ck_roam_session_status' AND constraint_type = 'CHECK'
);
SET @roam_session_status_check_sql := IF(
    @roam_session_status_check_exists = 0,
    'ALTER TABLE roam_session ADD CONSTRAINT ck_roam_session_status CHECK (status IN (0, 1))',
    'SELECT 1'
);
PREPARE roam_session_status_check_stmt FROM @roam_session_status_check_sql;
EXECUTE roam_session_status_check_stmt;
DEALLOCATE PREPARE roam_session_status_check_stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM information_schema.table_constraints
WHERE constraint_schema = DATABASE() AND table_name = 'roam_session'
  AND constraint_name = 'ck_roam_session_status' AND constraint_type = 'CHECK';
