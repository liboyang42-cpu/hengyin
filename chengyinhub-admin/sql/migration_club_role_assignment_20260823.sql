-- 俱乐部角色委派：CLUB 级副主理人/运营，EVENT 级领队/核销。
-- Owner 与 Member 始终从 club.member_id / active club_member 派生，不写入本表。
-- 前置：migration_business_audit_event_20260823.sql、存量 club migration。
-- 仅生成迁移文件；上线前必须单独走 chengyin-db-migrate 预检、执行与业务读回。

CREATE TABLE IF NOT EXISTS club_role_assignment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT NOT NULL,
  target_member_id BIGINT NOT NULL,
  role_code VARCHAR(32) NOT NULL COMMENT 'CLUB_CO_OWNER/CLUB_OPERATOR/EVENT_LEAD/EVENT_CHECKIN',
  scope_type VARCHAR(16) NOT NULL COMMENT 'CLUB/EVENT',
  scope_id BIGINT NOT NULL COMMENT 'CLUB=club.id；EVENT=cms_activity.id',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED/EXPIRED',
  expires_at DATETIME DEFAULT NULL,
  assigned_by_member_id BIGINT NOT NULL,
  assigned_at DATETIME NOT NULL,
  revoked_by_member_id BIGINT DEFAULT NULL,
  revoked_at DATETIME DEFAULT NULL,
  revoke_reason VARCHAR(500) DEFAULT NULL,
  version INT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  active_assignment_key VARCHAR(160) CHARACTER SET ascii GENERATED ALWAYS AS
    (CASE WHEN status = 'ACTIVE'
      THEN CONCAT(club_id, ':', target_member_id, ':', scope_type, ':', scope_id)
      ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_role_assignment_active (active_assignment_key),
  KEY idx_club_role_assignment_member (club_id, target_member_id, status, expires_at),
  KEY idx_club_role_assignment_scope (club_id, scope_type, scope_id, status, expires_at),
  CONSTRAINT chk_club_role_assignment_role CHECK
    (role_code IN ('CLUB_CO_OWNER','CLUB_OPERATOR','EVENT_LEAD','EVENT_CHECKIN')),
  CONSTRAINT chk_club_role_assignment_status CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
  CONSTRAINT chk_club_role_assignment_scope CHECK
    ((role_code IN ('CLUB_CO_OWNER','CLUB_OPERATOR') AND scope_type = 'CLUB' AND scope_id = club_id)
      OR (role_code IN ('EVENT_LEAD','EVENT_CHECKIN') AND scope_type = 'EVENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部作用域角色委派';

-- 先收敛已过期但仍占用 ACTIVE 唯一槽的历史委派；否则会阻断仍有效旧管理员回填。
UPDATE club_role_assignment
SET status = 'EXPIRED', update_by = 'migration', update_time = NOW()
WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= NOW();

-- 兼容既有 D22 管理员：只回填当前仍正常、俱乐部仍生效且尚无有效 CLUB 级 assignment 的记录。
-- 历史 REVOKED/EXPIRED 不能阻断当前管理员恢复为 ACTIVE；active 唯一键继续保证重复执行幂等。
-- 旧 role 字段暂保留，供旧版本回滚读取。
INSERT INTO club_role_assignment
  (club_id, target_member_id, role_code, scope_type, scope_id, status,
   expires_at, assigned_by_member_id, assigned_at, version,
   create_by, create_time, update_by, update_time)
SELECT cm.club_id, cm.member_id, 'CLUB_OPERATOR', 'CLUB', cm.club_id, 'ACTIVE',
       NULL, c.member_id, COALESCE(cm.join_time, NOW()), 0,
       CAST(c.member_id AS CHAR), NOW(), CAST(c.member_id AS CHAR), NOW()
FROM club_member cm
JOIN club c ON c.id = cm.club_id AND c.status = 1 AND c.del_flag = 0
WHERE cm.role = 1 AND cm.status = 1 AND cm.del_flag = 0
  AND NOT EXISTS (
    SELECT 1
    FROM club_role_assignment cra
    WHERE cra.club_id = cm.club_id
      AND cra.target_member_id = cm.member_id
      AND cra.scope_type = 'CLUB'
      AND cra.scope_id = cm.club_id
      AND cra.status = 'ACTIVE'
      AND (cra.expires_at IS NULL OR cra.expires_at > NOW())
  );

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND table_name = 'club_role_assignment' AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'club_role_assignment'
         AND column_name IN ('club_id','target_member_id','role_code','scope_type','scope_id','status',
           'expires_at','version','active_assignment_key')) = 9
  AND EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'club_role_assignment'
                AND column_name = 'active_assignment_key'
                AND extra LIKE '%STORED GENERATED%' AND generation_expression <> '')
  AND EXISTS (
    SELECT 1 FROM (
      SELECT index_name, MIN(non_unique) AS non_unique,
             GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
      FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'club_role_assignment'
      GROUP BY index_name
    ) exact_unique
    WHERE index_name = 'uk_club_role_assignment_active' AND non_unique = 0
      AND cols = 'active_assignment_key')
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND table_name = 'club_role_assignment'
         AND constraint_type = 'CHECK'
         AND constraint_name IN ('chk_club_role_assignment_role','chk_club_role_assignment_status',
           'chk_club_role_assignment_scope')) = 3
  AND (SELECT COUNT(*)
       FROM club_member cm
       JOIN club c ON c.id = cm.club_id AND c.status = 1 AND c.del_flag = 0
       LEFT JOIN club_role_assignment cra
         ON cra.club_id = cm.club_id
        AND cra.target_member_id = cm.member_id
        AND cra.scope_type = 'CLUB'
        AND cra.scope_id = cm.club_id
        AND cra.status = 'ACTIVE'
        AND (cra.expires_at IS NULL OR cra.expires_at > NOW())
       WHERE cm.role = 1 AND cm.status = 1 AND cm.del_flag = 0
         AND cra.id IS NULL) = 0
  AS assertion_ok;

SET SESSION group_concat_max_len = 65535;
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  AND SUM(table_name = 'club_role_assignment' AND column_count = 19
    AND column_hash = 'ccc3b7adc415ccf486399b075aac12b9efa95e1745b5b4d50635a228634c9c9d') = 1
  AS assertion_ok
FROM (
  SELECT table_name, COUNT(*) AS column_count,
         SHA2(GROUP_CONCAT(CONCAT_WS('~', column_name, LOWER(column_type), is_nullable,
           COALESCE(LOWER(CAST(column_default AS CHAR)), '<null>'), LOWER(extra),
           COALESCE(character_set_name, '<null>'),
           CASE WHEN character_set_name IS NULL OR character_set_name = 'utf8mb4'
             THEN COALESCE(character_set_name, '<null>') ELSE COALESCE(collation_name, '<null>') END,
           COALESCE(REGEXP_REPLACE(LOWER(generation_expression),
             '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), '<null>'))
           ORDER BY ordinal_position SEPARATOR '|'), 256) AS column_hash
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'club_role_assignment'
  GROUP BY table_name
) exact_columns;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  AND SUM(table_name = 'club_role_assignment' AND check_count = 3
    AND check_hash = '040e56193b66e9ad8e0fbc94f2d1c5c2b5975c7d15a0ac1c7598fb41714ef0bd') = 1
  AS assertion_ok
FROM (
  SELECT tc.table_name, COUNT(*) AS check_count,
         SHA2(GROUP_CONCAT(CONCAT_WS('~', cc.constraint_name, tc.enforced,
           SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
             '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256))
           ORDER BY cc.constraint_name SEPARATOR '|'), 256) AS check_hash
  FROM information_schema.table_constraints tc
  JOIN information_schema.check_constraints cc
    ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name
  WHERE tc.constraint_schema = DATABASE() AND tc.constraint_type = 'CHECK'
    AND tc.table_name = 'club_role_assignment'
  GROUP BY tc.table_name
) exact_checks;
