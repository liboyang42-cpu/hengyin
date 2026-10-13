-- 已核销真实商家评价：独立于 ums_comment，避免活动评论与核销资格/商家回复/平台审核语义串线。
-- 仅生成 SQL，不在本任务执行；依赖 business_audit_event_20260823、merchant_operator_20260823、
-- perk_ledger_20260716(redemption) 与 p3_platform_foundation(audit_task/audit_record)。

CREATE TABLE IF NOT EXISTS merchant_review (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_row_id BIGINT NOT NULL COMMENT 'mms_merchant.id 服务端快照',
  merchant_owner_member_id BIGINT NOT NULL COMMENT '目标商家 mms_merchant.member_id；与 redemption.merchant_id 同口径',
  registration_id BIGINT NOT NULL COMMENT 'cms_registration.id',
  redemption_id BIGINT NOT NULL COMMENT '创建时命中的 ACTIVE redemption.id',
  author_member_id BIGINT NOT NULL COMMENT '服务端从登录态绑定的评价人',
  rating TINYINT NOT NULL,
  content VARCHAR(1000) NOT NULL,
  images_json JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT 'PENDING_REVIEW/VISIBLE/HIDDEN；过审公开、违规软下架',
  reply_content VARCHAR(500) DEFAULT NULL COMMENT '商家唯一公开回复；不修改原评',
  reply_actor_member_id BIGINT DEFAULT NULL,
  reply_actor_role_code VARCHAR(32) DEFAULT NULL,
  reply_request_id VARCHAR(64) DEFAULT NULL,
  reply_fingerprint CHAR(64) DEFAULT NULL,
  replied_at DATETIME DEFAULT NULL,
  create_request_id VARCHAR(64) NOT NULL,
  create_fingerprint CHAR(64) NOT NULL,
  moderation_reason VARCHAR(500) DEFAULT NULL,
  moderated_at DATETIME DEFAULT NULL,
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_review_registration_merchant (registration_id, merchant_owner_member_id),
  UNIQUE KEY uk_merchant_review_author_request (author_member_id, create_request_id),
  UNIQUE KEY uk_merchant_review_reply_request (merchant_row_id, reply_request_id),
  KEY idx_merchant_review_public (merchant_row_id, status, create_time, id),
  KEY idx_merchant_review_author (author_member_id, create_time),
  CONSTRAINT ck_merchant_review_rating CHECK (rating BETWEEN 1 AND 5),
  CONSTRAINT ck_merchant_review_status CHECK (status IN ('PENDING_REVIEW', 'VISIBLE', 'HIDDEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已核销真实商家评价';

CREATE TABLE IF NOT EXISTS merchant_review_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  review_id BIGINT NOT NULL,
  merchant_row_id BIGINT NOT NULL,
  merchant_owner_member_id BIGINT NOT NULL,
  reporter_member_id BIGINT NOT NULL,
  reporter_role_code VARCHAR(32) NOT NULL,
  reason_text VARCHAR(500) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  audit_task_id BIGINT NOT NULL COMMENT '统一 audit_task.id；平台终态不在本表伪造',
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_review_report_request (reporter_member_id, request_id),
  KEY idx_merchant_review_report_review (review_id, create_time),
  KEY idx_merchant_review_report_task (audit_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价举报幂等回执';

-- 执行后结构读回（不在本任务执行）：残缺同名表必须让 runner 失败。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND engine = 'InnoDB'
     AND table_name IN ('merchant_review','merchant_review_report')) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND
        ((table_name = 'merchant_review' AND column_name IN
          ('merchant_row_id','merchant_owner_member_id','registration_id','redemption_id',
           'author_member_id','status','create_request_id','version'))
         OR (table_name = 'merchant_review_report' AND column_name IN
          ('review_id','merchant_row_id','merchant_owner_member_id','reporter_member_id','request_id','audit_task_id')))) = 14
  AND (SELECT COUNT(*) FROM (
        SELECT table_name, index_name, MIN(non_unique) AS non_unique,
               GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name IN ('merchant_review','merchant_review_report')
        GROUP BY table_name, index_name
      ) exact_unique
      WHERE non_unique = 0 AND
        ((table_name = 'merchant_review' AND index_name = 'uk_merchant_review_registration_merchant'
          AND cols = 'registration_id,merchant_owner_member_id')
         OR (table_name = 'merchant_review' AND index_name = 'uk_merchant_review_author_request'
          AND cols = 'author_member_id,create_request_id')
         OR (table_name = 'merchant_review' AND index_name = 'uk_merchant_review_reply_request'
          AND cols = 'merchant_row_id,reply_request_id')
         OR (table_name = 'merchant_review_report' AND index_name = 'uk_merchant_review_report_request'
          AND cols = 'reporter_member_id,request_id'))) = 4
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
         AND constraint_name IN ('ck_merchant_review_rating','ck_merchant_review_status')) = 2
  AS assertion_ok;

SET SESSION group_concat_max_len = 65535;
-- runner: assert=scalar-true
SELECT COUNT(*) = 2
  AND SUM(CASE
    WHEN table_name = 'merchant_review' AND column_count = 23
      AND column_hash = '15661b6eaa811587c70f6565bb5eceb07e4c6f759d9ffee59dcd57fd44711470' THEN 1
    WHEN table_name = 'merchant_review_report' AND column_count = 11
      AND column_hash = '19d639ddd2039ee83b895787b54025d9dd99e7407083e781e1923f555916e77b' THEN 1
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
  WHERE table_schema = DATABASE() AND table_name IN ('merchant_review','merchant_review_report')
  GROUP BY table_name
) exact_columns;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1
  AND SUM(table_name = 'merchant_review' AND check_count = 2
    AND check_hash = 'a5ffb90520794fa14adad2b446c4bef2819c2b92335eb658e4989abfa7e5d51a') = 1
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
    AND tc.table_name = 'merchant_review'
  GROUP BY tc.table_name
) exact_checks;
