-- 修复已提前落库的俱乐部 CHECK：MySQL CHECK 将 UNKNOWN 视为通过，旧等号表达式可被 NULL 绕过。
-- 依赖：migration_club_post_announcement_20260823.sql、migration_club_role_governance_20260823.sql。
-- 本文件只替换两个约束，不修改业务数据；ALTER 的 DROP+ADD 位于同一条原子 DDL 内。
-- 可重复执行：每次都先验证前镜像，再以同名约束替换并精确回读表达式。

-- 在删除旧约束前 fail closed：目标、约束、强制状态必须存在，且不得已有新规则判定为非法的数据。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*)
   FROM information_schema.table_constraints tc
   JOIN information_schema.check_constraints cc
     ON cc.constraint_schema = tc.constraint_schema
    AND cc.constraint_name = tc.constraint_name
   WHERE tc.constraint_schema = DATABASE()
     AND tc.constraint_type = 'CHECK'
     AND tc.enforced = 'YES'
     AND ((tc.table_name = 'club_post'
           AND tc.constraint_name = 'chk_club_post_pinned'
           AND SHA2(REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', ''), 256)
             IN ('96edec2bf807cbf809d410e84d835c9f6bd6a0be56d42ea6a285ac54a1c645e3',
                 'f9ba85873b52394c09bfb30afb76c372d4240b7ac7b9b0857952dfb9b0e1d684'))
       OR (tc.table_name = 'club_governance_case'
           AND tc.constraint_name = 'chk_club_governance_case_relation'
           AND SHA2(REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', ''), 256)
             IN ('083de7ee2dc1ad6262a3c7700ebe974491a3eb0e4b2f8c2e0f635dac739f18e4',
                 'a50edfff14921a1910de8aa9ccf6a1d90da8b4a8d8cca0ad8a6215b7f8b91d71')))) = 2
  AND (SELECT COALESCE(SUM(CASE WHEN
        is_pinned IN (0, 1) AND (is_pinned = 0 OR type <=> 2)
        THEN 0 ELSE 1 END), 0) FROM club_post) = 0
  AND (SELECT COALESCE(SUM(CASE WHEN
        (case_type = 'REPORT' AND target_type IN ('CLUB','ACTIVITY','MEMBER')
          AND related_ban_id IS NULL
          AND ((target_type = 'MEMBER' AND subject_member_id IS NOT NULL
              AND subject_member_id = target_id)
            OR (target_type IN ('CLUB','ACTIVITY') AND subject_member_id IS NULL)))
        OR (case_type = 'APPEAL' AND target_type = 'BAN'
          AND related_ban_id IS NOT NULL AND target_id = related_ban_id
          AND subject_member_id IS NOT NULL AND filer_member_id = subject_member_id)
        THEN 0 ELSE 1 END), 0) FROM club_governance_case) = 0
  AS assertion_ok;

ALTER TABLE club_post
  DROP CHECK chk_club_post_pinned,
  ADD CONSTRAINT chk_club_post_pinned CHECK
    (is_pinned IN (0, 1) AND (is_pinned = 0 OR type <=> 2)) ENFORCED;

ALTER TABLE club_governance_case
  DROP CHECK chk_club_governance_case_relation,
  ADD CONSTRAINT chk_club_governance_case_relation CHECK (
    (case_type = 'REPORT' AND target_type IN ('CLUB','ACTIVITY','MEMBER')
      AND related_ban_id IS NULL
      AND ((target_type = 'MEMBER' AND subject_member_id IS NOT NULL
          AND subject_member_id = target_id)
        OR (target_type IN ('CLUB','ACTIVITY') AND subject_member_id IS NULL)))
    OR (case_type = 'APPEAL' AND target_type = 'BAN'
      AND related_ban_id IS NOT NULL AND target_id = related_ban_id
      AND subject_member_id IS NOT NULL AND filer_member_id = subject_member_id)
  ) ENFORCED;

-- 精确回读归一后的 CHECK_CLAUSE；hash 钉住完整表达式，不接受只数约束名的假绿。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*)
   FROM information_schema.table_constraints tc
   JOIN information_schema.check_constraints cc
     ON cc.constraint_schema = tc.constraint_schema
    AND cc.constraint_name = tc.constraint_name
   WHERE tc.constraint_schema = DATABASE()
     AND tc.constraint_type = 'CHECK'
     AND tc.enforced = 'YES'
     AND ((tc.table_name = 'club_post'
           AND tc.constraint_name = 'chk_club_post_pinned'
           AND SHA2(REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', ''), 256)
             = 'f9ba85873b52394c09bfb30afb76c372d4240b7ac7b9b0857952dfb9b0e1d684')
       OR (tc.table_name = 'club_governance_case'
           AND tc.constraint_name = 'chk_club_governance_case_relation'
           AND SHA2(REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', ''), 256)
             = 'a50edfff14921a1910de8aa9ccf6a1d90da8b4a8d8cca0ad8a6215b7f8b91d71'))) = 2
  AND (SELECT COUNT(*) FROM club_post
       WHERE is_pinned = 1 AND (type IS NULL OR type <> 2)) = 0
  AND (SELECT COALESCE(SUM(CASE WHEN
        (case_type = 'REPORT' AND target_type IN ('CLUB','ACTIVITY','MEMBER')
          AND related_ban_id IS NULL
          AND ((target_type = 'MEMBER' AND subject_member_id IS NOT NULL
              AND subject_member_id = target_id)
            OR (target_type IN ('CLUB','ACTIVITY') AND subject_member_id IS NULL)))
        OR (case_type = 'APPEAL' AND target_type = 'BAN'
          AND related_ban_id IS NOT NULL AND target_id = related_ban_id
          AND subject_member_id IS NOT NULL AND filer_member_id = subject_member_id)
        THEN 0 ELSE 1 END), 0) FROM club_governance_case) = 0
  AS assertion_ok;
