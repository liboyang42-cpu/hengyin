-- 商家经营团队：员工关系与单次邀请。
-- 前置：migration_business_audit_event_20260823.sql。
-- 仅生成迁移文件；上线前必须单独走 chengyin-db-migrate 预检、执行与业务读回。

CREATE TABLE IF NOT EXISTS merchant_operator (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL COMMENT 'mms_merchant.id',
  operator_member_id BIGINT NOT NULL COMMENT 'ums_member.id，绝不改写其全局 role/user_type',
  active_operator_member_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN status = 'ACTIVE' THEN operator_member_id ELSE NULL END) STORED,
  role_code VARCHAR(32) NOT NULL COMMENT 'MERCHANT_MANAGER/CHECKIN/MARKETING/FINANCE',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED',
  accepted_at DATETIME NOT NULL,
  revoked_by_member_id BIGINT DEFAULT NULL,
  revoked_at DATETIME DEFAULT NULL,
  revoke_reason VARCHAR(500) DEFAULT NULL,
  version INT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_operator_active_member (active_operator_member_id),
  KEY idx_merchant_operator_team (merchant_id, status, id),
  KEY idx_merchant_operator_history (operator_member_id, id),
  CONSTRAINT chk_merchant_operator_role CHECK
    (role_code IN ('MERCHANT_MANAGER','MERCHANT_CHECKIN','MERCHANT_MARKETING','MERCHANT_FINANCE')),
  CONSTRAINT chk_merchant_operator_status CHECK (status IN ('ACTIVE','REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家经营团队员工关系';

CREATE TABLE IF NOT EXISTS merchant_operator_invite (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_id BIGINT NOT NULL COMMENT 'mms_merchant.id',
  role_code VARCHAR(32) NOT NULL,
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256；原 token 不落库',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REVOKED/EXPIRED',
  invited_by_member_id BIGINT NOT NULL,
  invited_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  accepted_by_member_id BIGINT DEFAULT NULL,
  accepted_at DATETIME DEFAULT NULL,
  revoked_by_member_id BIGINT DEFAULT NULL,
  revoked_at DATETIME DEFAULT NULL,
  revoke_reason VARCHAR(500) DEFAULT NULL,
  request_id VARCHAR(64) DEFAULT NULL COMMENT '同一商家创建邀请的幂等键',
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_operator_invite_token (token_hash),
  UNIQUE KEY uk_merchant_operator_invite_request (merchant_id, request_id),
  KEY idx_merchant_operator_invite_team (merchant_id, status, expires_at),
  CONSTRAINT chk_merchant_operator_invite_role CHECK
    (role_code IN ('MERCHANT_MANAGER','MERCHANT_CHECKIN','MERCHANT_MARKETING','MERCHANT_FINANCE')),
  CONSTRAINT chk_merchant_operator_invite_status CHECK
    (status IN ('PENDING','ACCEPTED','REVOKED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家经营团队单次邀请';

-- 结构读回必须由执行器断言；同名残缺表不能因 IF NOT EXISTS 被误判为成功。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND engine = 'InnoDB'
     AND table_name IN ('merchant_operator','merchant_operator_invite')) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND
         ((table_name = 'merchant_operator' AND column_name IN
           ('merchant_id','operator_member_id','active_operator_member_id','role_code','status','version'))
          OR (table_name = 'merchant_operator_invite' AND column_name IN
           ('merchant_id','role_code','token_hash','status','request_id','version')))) = 12
  AND EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'merchant_operator'
                AND column_name = 'active_operator_member_id'
                AND extra LIKE '%STORED GENERATED%' AND generation_expression <> '')
  AND (SELECT COUNT(*) FROM (
        SELECT table_name, index_name, MIN(non_unique) AS non_unique,
               GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name IN ('merchant_operator','merchant_operator_invite')
        GROUP BY table_name, index_name
      ) exact_unique
      WHERE non_unique = 0 AND
        ((table_name = 'merchant_operator' AND index_name = 'uk_merchant_operator_active_member'
          AND cols = 'active_operator_member_id')
         OR (table_name = 'merchant_operator_invite' AND index_name = 'uk_merchant_operator_invite_token'
          AND cols = 'token_hash')
         OR (table_name = 'merchant_operator_invite' AND index_name = 'uk_merchant_operator_invite_request'
          AND cols = 'merchant_id,request_id'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
         AND constraint_name IN ('chk_merchant_operator_role','chk_merchant_operator_status',
           'chk_merchant_operator_invite_role','chk_merchant_operator_invite_status')) = 4
  AS assertion_ok;

-- 候选同名表的全列与生成表达式精确签名。
SET SESSION group_concat_max_len = 65535;
-- runner: assert=scalar-true
SELECT COUNT(*) = 2
  AND SUM(CASE
    WHEN table_name = 'merchant_operator' AND column_count = 15
      AND column_hash = 'fad84dd726fbf1b7138ba2e01167220fa324eec996a16bc2b2b2aa82a289e4d3' THEN 1
    WHEN table_name = 'merchant_operator_invite' AND column_count = 17
      AND column_hash = 'ea63bad38792d85cc03a7c8051dc189f9140b2dda5bbcf23029896f2f12e4ed1' THEN 1
    ELSE 0 END) = 2 AS assertion_ok
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
    AND table_name IN ('merchant_operator','merchant_operator_invite')
  GROUP BY table_name
) exact_columns;

-- 四条权限/状态 CHECK 名称、ENFORCED 与保留括号分组的正文精确签名。
-- runner: assert=scalar-true
SELECT COUNT(*) = 2
  AND SUM(CASE
    WHEN table_name = 'merchant_operator' AND check_count = 2
      AND check_hash = 'a9212f9b3cd42111b861b555c21c2b0b42444db92da1a2b4c52b6b13a86577a8' THEN 1
    WHEN table_name = 'merchant_operator_invite' AND check_count = 2
      AND check_hash = '84dbf097e1f586edba1177c2645affb4a1447ecdd5d3fbce80b017d99982a2dd' THEN 1
    ELSE 0 END) = 2 AS assertion_ok
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
    AND tc.table_name IN ('merchant_operator','merchant_operator_invite')
  GROUP BY tc.table_name
) exact_checks;
