-- 俱乐部付费会员与会费 Phase 1D。
-- 前置：migration_club_role_assignment_20260823.sql、migration_business_audit_event_20260823.sql。
-- 免费成员身份仍以 club_member 为真源；本迁移的 setting/plan/invoice/term/review 不复用活动票或报名表。
-- 仅生成迁移文件，未执行任何数据库写入；上线前须单独走 chengyin-db-migrate 预检、执行、业务读回。

CREATE TABLE IF NOT EXISTS club_membership_setting (
  club_id BIGINT NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '缺行或0=会费关闭；不影响免费成员',
  version INT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (club_id),
  CONSTRAINT chk_club_membership_setting_enabled CHECK (enabled IN (0, 1)),
  CONSTRAINT fk_club_membership_setting_club FOREIGN KEY (club_id) REFERENCES club(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部会费开关';

CREATE TABLE IF NOT EXISTS club_membership_plan (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT NOT NULL,
  name VARCHAR(40) NOT NULL,
  duration_days INT NOT NULL,
  price_fen BIGINT NOT NULL COMMENT '人民币分；创建账单后冻结',
  benefits_json JSON NOT NULL COMMENT '受控权益文本数组',
  grace_days INT NOT NULL DEFAULT 0,
  past_due_days INT NOT NULL DEFAULT 30 COMMENT '宽限后逾期保留窗口；无权益',
  exit_policy VARCHAR(24) NOT NULL COMMENT '首版仅END_IMMEDIATELY；退出/封禁/解散立即停止权益',
  refund_policy VARCHAR(24) NOT NULL COMMENT 'NON_REFUNDABLE/MANUAL_REVIEW',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  version INT NOT NULL DEFAULT 0,
  -- ⚠️ ums_member.id 是 BIGINT UNSIGNED；外键要求两侧列类型完全一致，
  --    写成有符号 BIGINT 会在建表时直接报 3780 incompatible。club.id 是有符号的，
  --    所以引用 club 的外键没事 —— 别照抄它的写法。
  created_by_member_id BIGINT UNSIGNED NOT NULL,
  del_flag TINYINT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_club_membership_plan_club (club_id, status, del_flag, price_fen),
  CONSTRAINT chk_club_membership_plan_duration CHECK (duration_days BETWEEN 1 AND 3650),
  CONSTRAINT chk_club_membership_plan_price CHECK (price_fen BETWEEN 1 AND 100000000),
  CONSTRAINT chk_club_membership_plan_grace CHECK (grace_days BETWEEN 0 AND 30),
  CONSTRAINT chk_club_membership_plan_past_due CHECK (past_due_days BETWEEN 0 AND 90),
  CONSTRAINT chk_club_membership_plan_exit CHECK (exit_policy = 'END_IMMEDIATELY'),
  CONSTRAINT chk_club_membership_plan_refund CHECK (refund_policy IN ('NON_REFUNDABLE','MANUAL_REVIEW')),
  CONSTRAINT chk_club_membership_plan_status CHECK (status IN ('ACTIVE','INACTIVE')),
  CONSTRAINT fk_club_membership_plan_club FOREIGN KEY (club_id) REFERENCES club(id),
  CONSTRAINT fk_club_membership_plan_actor FOREIGN KEY (created_by_member_id) REFERENCES ums_member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部会费计划';

CREATE TABLE IF NOT EXISTS club_membership_invoice (
  id BIGINT NOT NULL AUTO_INCREMENT,
  invoice_no VARCHAR(32) CHARACTER SET ascii NOT NULL,
  out_trade_no VARCHAR(32) CHARACTER SET ascii NOT NULL COMMENT 'CLM+29位hex，专属微信订单路由',
  club_id BIGINT NOT NULL,
  owner_member_id_snapshot BIGINT NOT NULL COMMENT '下单时俱乐部主理人快照；转让后仍可验旧单',
  -- ⚠️ ums_member.id 是 BIGINT UNSIGNED；外键要求两侧列类型完全一致，
  --    写成有符号 BIGINT 会在建表时直接报 3780 incompatible。club.id 是有符号的，
  --    所以引用 club 的外键没事 —— 别照抄它的写法。
  member_id BIGINT UNSIGNED NOT NULL,
  plan_id BIGINT NOT NULL,
  plan_version_snapshot INT NOT NULL,
  plan_name_snapshot VARCHAR(40) NOT NULL,
  duration_days_snapshot INT NOT NULL,
  amount_fen BIGINT NOT NULL,
  benefits_snapshot JSON NOT NULL,
  grace_days_snapshot INT NOT NULL,
  past_due_days_snapshot INT NOT NULL,
  exit_policy_snapshot VARCHAR(24) NOT NULL,
  refund_policy_snapshot VARCHAR(24) NOT NULL,
  snapshot_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT 'CREATED/PAYING/PAID/CLOSED',
  active_paying_member_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN status = 'PAYING' THEN member_id ELSE NULL END) STORED
    COMMENT '仅PAYING时投影member_id，与club_id组合阻止不同key并发双账单',
  idempotency_key VARCHAR(64) CHARACTER SET ascii NOT NULL,
  request_fingerprint CHAR(64) CHARACTER SET ascii NOT NULL,
  transaction_id VARCHAR(64) CHARACTER SET ascii DEFAULT NULL,
  pay_params_json JSON DEFAULT NULL COMMENT '预支付成功后保存，网络异常账单仍可查询',
  paid_at DATETIME DEFAULT NULL,
  version INT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_membership_invoice_no (invoice_no),
  UNIQUE KEY uk_club_membership_out_trade_no (out_trade_no),
  UNIQUE KEY uk_club_membership_idempotency (club_id, member_id, idempotency_key),
  UNIQUE KEY uk_club_membership_one_paying (club_id, active_paying_member_id),
  UNIQUE KEY uk_club_membership_transaction (transaction_id),
  KEY idx_club_membership_invoice_finance (club_id, status, create_time),
  KEY idx_club_membership_invoice_member (club_id, member_id, create_time),
  CONSTRAINT chk_club_membership_invoice_prefix CHECK (out_trade_no REGEXP '^CLM[0-9a-f]{29}$'),
  CONSTRAINT chk_club_membership_invoice_amount CHECK (amount_fen BETWEEN 1 AND 100000000),
  CONSTRAINT chk_club_membership_invoice_exit CHECK (exit_policy_snapshot = 'END_IMMEDIATELY'),
  CONSTRAINT chk_club_membership_invoice_refund CHECK (refund_policy_snapshot IN ('NON_REFUNDABLE','MANUAL_REVIEW')),
  CONSTRAINT chk_club_membership_invoice_status CHECK (status IN ('CREATED','PAYING','PAID','CLOSED')),
  CONSTRAINT fk_club_membership_invoice_club FOREIGN KEY (club_id) REFERENCES club(id),
  CONSTRAINT fk_club_membership_invoice_member FOREIGN KEY (member_id) REFERENCES ums_member(id),
  CONSTRAINT fk_club_membership_invoice_plan FOREIGN KEY (plan_id) REFERENCES club_membership_plan(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部会费不可变账单';

CREATE TABLE IF NOT EXISTS club_membership_term (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT NOT NULL,
  -- ⚠️ ums_member.id 是 BIGINT UNSIGNED；外键要求两侧列类型完全一致，
  --    写成有符号 BIGINT 会在建表时直接报 3780 incompatible。club.id 是有符号的，
  --    所以引用 club 的外键没事 —— 别照抄它的写法。
  member_id BIGINT UNSIGNED NOT NULL,
  plan_id BIGINT NOT NULL,
  source_invoice_id BIGINT NOT NULL COMMENT '一张 PAID 账单至多创建一个权益期',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING/ACTIVE/GRACE/PAST_DUE/EXPIRED/CANCELLED/REFUNDED',
  starts_at DATETIME NOT NULL,
  ends_at DATETIME NOT NULL,
  grace_ends_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  benefits_snapshot JSON NOT NULL,
  exit_policy_snapshot VARCHAR(24) NOT NULL,
  refund_policy_snapshot VARCHAR(24) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_membership_term_invoice (source_invoice_id),
  KEY idx_club_membership_term_entitlement (club_id, member_id, starts_at, grace_ends_at),
  CONSTRAINT chk_club_membership_term_status CHECK
    (status IN ('PENDING','ACTIVE','GRACE','PAST_DUE','EXPIRED','CANCELLED','REFUNDED')),
  CONSTRAINT chk_club_membership_term_time CHECK
    (starts_at < ends_at AND ends_at <= grace_ends_at AND grace_ends_at <= expires_at),
  CONSTRAINT chk_club_membership_term_exit CHECK (exit_policy_snapshot = 'END_IMMEDIATELY'),
  CONSTRAINT fk_club_membership_term_club FOREIGN KEY (club_id) REFERENCES club(id),
  CONSTRAINT fk_club_membership_term_member FOREIGN KEY (member_id) REFERENCES ums_member(id),
  CONSTRAINT fk_club_membership_term_plan FOREIGN KEY (plan_id) REFERENCES club_membership_plan(id),
  CONSTRAINT fk_club_membership_term_invoice FOREIGN KEY (source_invoice_id) REFERENCES club_membership_invoice(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部付费会员权益期';

CREATE TABLE IF NOT EXISTS club_membership_refund_review (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT NOT NULL,
  -- ⚠️ ums_member.id 是 BIGINT UNSIGNED；外键要求两侧列类型完全一致，
  --    写成有符号 BIGINT 会在建表时直接报 3780 incompatible。club.id 是有符号的，
  --    所以引用 club 的外键没事 —— 别照抄它的写法。
  member_id BIGINT UNSIGNED NOT NULL,
  invoice_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL COMMENT '首版仅PENDING_REVIEW；只表示平台审核中，不表示已退款',
  reason VARCHAR(500) NOT NULL,
  request_id VARCHAR(96) CHARACTER SET ascii NOT NULL,
  version INT NOT NULL DEFAULT 0,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_membership_refund_invoice (invoice_id),
  UNIQUE KEY uk_club_membership_refund_request (club_id, member_id, request_id),
  KEY idx_club_membership_refund_queue (status, create_time),
  CONSTRAINT chk_club_membership_refund_status CHECK (status = 'PENDING_REVIEW'),
  CONSTRAINT fk_club_membership_refund_club FOREIGN KEY (club_id) REFERENCES club(id),
  CONSTRAINT fk_club_membership_refund_member FOREIGN KEY (member_id) REFERENCES ums_member(id),
  CONSTRAINT fk_club_membership_refund_invoice FOREIGN KEY (invoice_id) REFERENCES club_membership_invoice(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部会费退款人工审核事实（非打款终态）';

INSERT INTO sys_config
  (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-俱乐部付费会员', 'feature.flag.clubMembership', 'false', 'N', 'admin', NOW(),
       '默认关闭；后端接口与小程序均 fail-closed。支付回调仍处理已在途 CLM 账单，防止已付款不落权益。'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.clubMembership');

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND engine = 'InnoDB'
     AND table_name IN ('club_membership_setting','club_membership_plan','club_membership_invoice',
       'club_membership_term','club_membership_refund_review')) = 5
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND
        ((table_name = 'club_membership_setting' AND column_name IN ('club_id','enabled','version'))
         OR (table_name = 'club_membership_plan' AND column_name IN
          ('club_id','duration_days','price_fen','benefits_json','grace_days','past_due_days','exit_policy','refund_policy','status','version'))
         OR (table_name = 'club_membership_invoice' AND column_name IN
          ('invoice_no','out_trade_no','club_id','owner_member_id_snapshot','member_id','plan_id',
           'amount_fen','snapshot_hash','status','active_paying_member_id','idempotency_key','request_fingerprint','transaction_id','version'))
         OR (table_name = 'club_membership_term' AND column_name IN
          ('club_id','member_id','plan_id','source_invoice_id','status','starts_at','ends_at','grace_ends_at','expires_at','version'))
         OR (table_name = 'club_membership_refund_review' AND column_name IN
          ('club_id','member_id','invoice_id','status','request_id','version')))) = 43
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'club_membership_invoice'
         AND column_name = 'active_paying_member_id' AND extra = 'STORED GENERATED'
         AND generation_expression LIKE '%status%'
         AND generation_expression LIKE '%PAYING%'
         AND generation_expression LIKE '%member_id%') = 1
  AND (SELECT COUNT(*) FROM (
        SELECT table_name, index_name, MIN(non_unique) AS non_unique,
               GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name LIKE 'club_membership_%'
        GROUP BY table_name, index_name
      ) exact_unique
      WHERE non_unique = 0 AND
       ((table_name = 'club_membership_invoice' AND index_name = 'uk_club_membership_invoice_no' AND cols = 'invoice_no')
        OR (table_name = 'club_membership_invoice' AND index_name = 'uk_club_membership_out_trade_no' AND cols = 'out_trade_no')
        OR (table_name = 'club_membership_invoice' AND index_name = 'uk_club_membership_idempotency' AND cols = 'club_id,member_id,idempotency_key')
        OR (table_name = 'club_membership_invoice' AND index_name = 'uk_club_membership_one_paying' AND cols = 'club_id,active_paying_member_id')
        OR (table_name = 'club_membership_invoice' AND index_name = 'uk_club_membership_transaction' AND cols = 'transaction_id')
        OR (table_name = 'club_membership_term' AND index_name = 'uk_club_membership_term_invoice' AND cols = 'source_invoice_id')
        OR (table_name = 'club_membership_refund_review' AND index_name = 'uk_club_membership_refund_invoice' AND cols = 'invoice_id')
        OR (table_name = 'club_membership_refund_review' AND index_name = 'uk_club_membership_refund_request' AND cols = 'club_id,member_id,request_id'))) = 8
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
         AND table_name LIKE 'club_membership_%') = 17
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'
         AND table_name LIKE 'club_membership_%') = 13
  AND (SELECT COUNT(*) FROM sys_config
       WHERE config_key = 'feature.flag.clubMembership' AND config_value = 'false') = 1
  AND (SELECT COUNT(*) FROM club_membership_invoice
       WHERE out_trade_no NOT REGEXP '^CLM[0-9a-f]{29}$') = 0
  AS assertion_ok;
