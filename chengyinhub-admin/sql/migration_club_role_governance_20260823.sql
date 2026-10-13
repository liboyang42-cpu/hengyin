-- 俱乐部成员治理：限时/平台永久封禁、主理人转让、举报申诉与高风险动作审计。
-- 字典序依赖：migration_club_role_assignment_20260823.sql 先执行。
-- 依赖 migration_business_audit_event_20260823.sql 已创建 business_audit_event，
-- 且 migration_club_role_assignment_20260823.sql 已创建俱乐部动态角色边界。
-- 仅生成迁移文件；上线前必须单独走 chengyin-db-migrate 预检、执行与业务读回。
--
-- 执行前预检（第一条应返回 0 行；第二条应恰好返回 business_audit_event）：
-- SELECT table_name FROM information_schema.tables
-- WHERE table_schema = DATABASE()
--   AND table_name IN ('club_governance_ban','club_governance_owner_transfer','club_governance_case');
-- SELECT table_name FROM information_schema.tables
-- WHERE table_schema = DATABASE() AND table_name = 'business_audit_event';

CREATE TABLE IF NOT EXISTS club_governance_ban (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT NOT NULL,
  target_member_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/UNBANNED/EXPIRED',
  source_type VARCHAR(16) NOT NULL COMMENT 'CLUB=最长90天；PLATFORM=平台永久封禁',
  ban_reason VARCHAR(500) NOT NULL,
  expires_at DATETIME DEFAULT NULL COMMENT '仅 PLATFORM 可为 NULL；CLUB 必须晚于封禁时间且最长90天',
  banned_by_member_id BIGINT NOT NULL,
  banned_at DATETIME NOT NULL,
  unbanned_by_member_id BIGINT DEFAULT NULL,
  unbanned_at DATETIME DEFAULT NULL,
  unban_reason VARCHAR(500) DEFAULT NULL,
  ban_request_id VARCHAR(64) NOT NULL,
  unban_request_id VARCHAR(64) DEFAULT NULL,
  version INT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  active_ban_key VARCHAR(96) CHARACTER SET ascii GENERATED ALWAYS AS
    (CASE WHEN status = 'ACTIVE' THEN CONCAT(club_id, ':', target_member_id) ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_governance_active (active_ban_key),
  UNIQUE KEY uk_club_governance_ban_request (club_id, ban_request_id),
  UNIQUE KEY uk_club_governance_unban_request (club_id, unban_request_id),
  KEY idx_club_governance_history (club_id, banned_at, id),
  KEY idx_club_governance_member (club_id, target_member_id, status, expires_at),
  CONSTRAINT chk_club_governance_status CHECK (status IN ('ACTIVE','UNBANNED','EXPIRED')),
  CONSTRAINT chk_club_governance_source CHECK (source_type IN ('CLUB','PLATFORM')),
  CONSTRAINT chk_club_governance_expiry CHECK (
    (source_type = 'CLUB' AND expires_at IS NOT NULL
      AND expires_at > banned_at AND expires_at <= DATE_ADD(banned_at, INTERVAL 90 DAY))
    OR (source_type = 'PLATFORM' AND expires_at IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部成员封禁与解封留痕';

CREATE TABLE IF NOT EXISTS club_governance_owner_transfer (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT NOT NULL,
  from_owner_member_id BIGINT NOT NULL,
  to_owner_member_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  transferred_at DATETIME NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_owner_transfer_request (club_id, request_id),
  KEY idx_club_owner_transfer_history (club_id, transferred_at, id),
  CONSTRAINT chk_club_owner_transfer_members CHECK (from_owner_member_id <> to_owner_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部主理人转让不可变历史';

CREATE TABLE IF NOT EXISTS club_governance_case (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT NOT NULL,
  case_type VARCHAR(16) NOT NULL COMMENT 'REPORT/APPEAL',
  filer_member_id BIGINT NOT NULL,
  subject_member_id BIGINT DEFAULT NULL COMMENT '仅 MEMBER 举报/申诉保留的成员作用域',
  target_type VARCHAR(16) NOT NULL COMMENT 'REPORT=CLUB/ACTIVITY/MEMBER；APPEAL=BAN',
  target_id BIGINT NOT NULL,
  evidence_json VARCHAR(1000) NOT NULL DEFAULT '{}' COMMENT '受控证据键值，仅平台审核端可见',
  related_ban_id BIGINT DEFAULT NULL,
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  decision_reason VARCHAR(500) DEFAULT NULL,
  decided_by_user_id BIGINT DEFAULT NULL COMMENT 'sys_user.user_id，仅平台审核接口写入',
  decided_at DATETIME DEFAULT NULL,
  submit_request_id VARCHAR(64) NOT NULL,
  decision_request_id VARCHAR(64) DEFAULT NULL,
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME DEFAULT NULL,
  pending_appeal_key BIGINT GENERATED ALWAYS AS
    (CASE WHEN case_type = 'APPEAL' AND status = 'PENDING' THEN related_ban_id ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_governance_case_submit (club_id, filer_member_id, submit_request_id),
  UNIQUE KEY uk_club_governance_case_decision (club_id, decision_request_id),
  UNIQUE KEY uk_club_governance_pending_appeal (pending_appeal_key),
  KEY idx_club_governance_case_queue (status, case_type, create_time, id),
  KEY idx_club_governance_case_filer (club_id, filer_member_id, create_time, id),
  KEY idx_club_governance_case_target (club_id, target_type, target_id, create_time, id),
  CONSTRAINT chk_club_governance_case_type CHECK (case_type IN ('REPORT','APPEAL')),
  CONSTRAINT chk_club_governance_case_status CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  CONSTRAINT chk_club_governance_case_evidence CHECK (JSON_VALID(evidence_json)),
  CONSTRAINT chk_club_governance_case_relation CHECK (
    (case_type = 'REPORT' AND target_type IN ('CLUB','ACTIVITY','MEMBER')
      AND related_ban_id IS NULL
      AND ((target_type = 'MEMBER' AND subject_member_id IS NOT NULL
        AND subject_member_id = target_id)
        OR (target_type IN ('CLUB','ACTIVITY') AND subject_member_id IS NULL)))
    OR (case_type = 'APPEAL' AND target_type = 'BAN'
      AND related_ban_id IS NOT NULL AND target_id = related_ban_id
      AND subject_member_id IS NOT NULL AND filer_member_id = subject_member_id)
  ) ENFORCED
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部治理举报与申诉平台队列';

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND engine = 'InnoDB'
     AND table_name IN ('club_governance_ban','club_governance_owner_transfer','club_governance_case')) = 3
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND
        ((table_name = 'club_governance_ban' AND column_name IN
          ('club_id','target_member_id','status','source_type','expires_at','ban_request_id','unban_request_id','version','active_ban_key'))
         OR (table_name = 'club_governance_owner_transfer' AND column_name IN
          ('club_id','from_owner_member_id','to_owner_member_id','request_id'))
         OR (table_name = 'club_governance_case' AND column_name IN
          ('club_id','case_type','filer_member_id','target_type','target_id','status','submit_request_id',
           'decision_request_id','version','pending_appeal_key')))) = 23
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE()
         AND ((table_name = 'club_governance_ban' AND column_name = 'active_ban_key')
           OR (table_name = 'club_governance_case' AND column_name = 'pending_appeal_key'))
         AND extra LIKE '%STORED GENERATED%' AND generation_expression <> '') = 2
  AND (SELECT COUNT(*) FROM (
        SELECT table_name, index_name, MIN(non_unique) AS non_unique,
               GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name IN ('club_governance_ban','club_governance_owner_transfer','club_governance_case')
        GROUP BY table_name, index_name
      ) exact_unique
      WHERE non_unique = 0 AND
       ((table_name = 'club_governance_ban' AND index_name = 'uk_club_governance_active' AND cols = 'active_ban_key')
        OR (table_name = 'club_governance_ban' AND index_name = 'uk_club_governance_ban_request' AND cols = 'club_id,ban_request_id')
        OR (table_name = 'club_governance_ban' AND index_name = 'uk_club_governance_unban_request' AND cols = 'club_id,unban_request_id')
        OR (table_name = 'club_governance_owner_transfer' AND index_name = 'uk_club_owner_transfer_request' AND cols = 'club_id,request_id')
        OR (table_name = 'club_governance_case' AND index_name = 'uk_club_governance_case_submit' AND cols = 'club_id,filer_member_id,submit_request_id')
        OR (table_name = 'club_governance_case' AND index_name = 'uk_club_governance_case_decision' AND cols = 'club_id,decision_request_id')
        OR (table_name = 'club_governance_case' AND index_name = 'uk_club_governance_pending_appeal' AND cols = 'pending_appeal_key'))) = 7
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
         AND enforced = 'YES'
         AND table_name IN ('club_governance_ban','club_governance_owner_transfer','club_governance_case')) = 8
  AND EXISTS (
    SELECT 1
    FROM information_schema.table_constraints tc
    JOIN information_schema.check_constraints cc
      ON cc.constraint_schema = tc.constraint_schema
     AND cc.constraint_name = tc.constraint_name
    WHERE tc.constraint_schema = DATABASE()
      AND tc.table_name = 'club_governance_case'
      AND tc.constraint_name = 'chk_club_governance_case_relation'
      AND tc.enforced = 'YES'
      AND SHA2(REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', ''), 256)
        = 'a50edfff14921a1910de8aa9ccf6a1d90da8b4a8d8cca0ad8a6215b7f8b91d71')
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

SET SESSION group_concat_max_len = 65535;
-- runner: assert=scalar-true
SELECT COUNT(*) = 3
  AND SUM(CASE
    WHEN table_name = 'club_governance_ban' AND column_count = 20
      AND column_hash = '4b38730d38e2f6b348f12a811be74554349807dbcadeebce3169d43f86adc00c' THEN 1
    WHEN table_name = 'club_governance_owner_transfer' AND column_count = 8
      AND column_hash = '206c444ccf03e678c7f96cab6e73ac4abff6d3173ffc52ef7437120283cfa3b2' THEN 1
    WHEN table_name = 'club_governance_case' AND column_count = 20
      AND column_hash = '2f1322f38f87879fbe1eba05642e50103660ab566a9a90a0c96236e96b30d266' THEN 1
    ELSE 0 END) = 3 AS assertion_ok
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
  WHERE table_schema = DATABASE()
    AND table_name IN ('club_governance_ban','club_governance_owner_transfer','club_governance_case')
  GROUP BY table_name
) exact_columns;

-- runner: assert=scalar-true
SELECT COUNT(*) = 3
  AND SUM(CASE
    WHEN table_name = 'club_governance_ban' AND check_count = 3
      AND check_hash = 'f86a88e011fb7317602243bf8dd8ad499613046178c8a250d8ec72d41219702b' THEN 1
    WHEN table_name = 'club_governance_owner_transfer' AND check_count = 1
      AND check_hash = '51009325bbc6ba7e3a3e0f54b580dc26b3d1b001714158eb265f2103467342d8' THEN 1
    WHEN table_name = 'club_governance_case' AND check_count = 4
      AND check_hash = '535473dc2fe9455acacc4c62ee78962121799e797e5d586f8551c2bcc498ae84' THEN 1
    ELSE 0 END) = 3 AS assertion_ok
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
    AND tc.table_name IN ('club_governance_ban','club_governance_owner_transfer','club_governance_case')
  GROUP BY tc.table_name
) exact_checks;
