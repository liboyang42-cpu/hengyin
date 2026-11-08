-- P5 已付多人票「内含席位」邀请生命周期（2026-11-08，候选，不自动执行）。
-- 只新增邀请/幂等回执两张事实表；不改 cms_registration / cms_registration_seat /
-- gift_entitlement / 订单 / 钱包 / 结算。生产执行必须走 chengyin-db-migrate。
--
-- 语义：原 cms_registration 仍是唯一已付/财务权威；order_num 是下单时冻结的席位数。
-- seat_no=1 为购票人自用，可邀请 seat_no=2..order_num，最多 order_num-1 个有效邀请。
-- active_flag 让「同一原单同一席同时最多一个待领取/已领取」，终态行保留历史。

-- 预检：同名表若已存在但形状不对，下面的断言必须失败，绝不静默沿用坏表。
SET @seat_invitation_table_exists := (
    SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation');

SET @seat_invitation_shape_ok := (
    @seat_invitation_table_exists = 0
    OR (
        SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation'
           AND (
             (column_name = 'id' AND column_type = 'bigint' AND is_nullable = 'NO' AND extra = 'auto_increment') OR
             (column_name = 'registration_id' AND column_type = 'bigint' AND is_nullable = 'NO') OR
             (column_name = 'seat_no' AND column_type = 'int' AND is_nullable = 'NO') OR
             (column_name = 'generation' AND column_type = 'int' AND is_nullable = 'NO' AND column_default = '1') OR
             (column_name = 'owner_member_id' AND column_type = 'bigint' AND is_nullable = 'NO') OR
             (column_name = 'owner_type' AND column_type = 'bigint' AND is_nullable = 'NO') OR
             (column_name = 'owner_id' AND column_type = 'bigint' AND is_nullable = 'NO') OR
             (column_name = 'topic_id' AND column_type = 'bigint' AND is_nullable = 'YES') OR
             (column_name = 'status' AND column_type = 'tinyint' AND is_nullable = 'NO' AND column_default = '0') OR
             (column_name = 'token_nonce' AND column_type = 'char(16)' AND is_nullable = 'NO') OR
             (column_name = 'token_hash' AND column_type = 'char(64)' AND is_nullable = 'NO') OR
             (column_name = 'expires_at' AND column_type = 'datetime(3)' AND is_nullable = 'NO') OR
             (column_name = 'invitee_member_id' AND column_type = 'bigint' AND is_nullable = 'YES') OR
             (column_name = 'claimed_at' AND column_type = 'datetime(3)' AND is_nullable = 'YES') OR
             (column_name = 'create_time' AND column_type = 'datetime(3)' AND is_nullable = 'NO') OR
             (column_name = 'update_time' AND column_type = 'datetime(3)' AND is_nullable = 'NO') OR
             (column_name = 'version' AND column_type = 'int' AND is_nullable = 'NO' AND column_default = '0') OR
             (column_name = 'active_flag' AND column_type = 'tinyint' AND is_nullable = 'YES'
                AND extra LIKE '%STORED GENERATED%')
           )
    ) = 18
);
-- runner: assert=scalar-true
SELECT @seat_invitation_shape_ok AS cms_registration_seat_invitation_precheck;
SET @seat_invitation_guard_sql := IF(@seat_invitation_shape_ok,
    'SELECT 1', 'SELECT * FROM information_schema.__seat_invitation_precheck_failed__');
PREPARE seat_invitation_guard FROM @seat_invitation_guard_sql;
EXECUTE seat_invitation_guard;
DEALLOCATE PREPARE seat_invitation_guard;

CREATE TABLE IF NOT EXISTS cms_registration_seat_invitation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  registration_id BIGINT NOT NULL COMMENT '原 cms_registration.id；唯一已付权威',
  seat_no INT NOT NULL COMMENT '内含席号 2..order_num；1 为购票人自用',
  generation INT NOT NULL DEFAULT 1 COMMENT '同单同席的邀请代次；旧码/旧行互不覆盖',
  owner_member_id BIGINT NOT NULL COMMENT '购票人（= 原行 member_id）',
  owner_type BIGINT NOT NULL COMMENT '1=主题 2=活动；原行快照',
  owner_id BIGINT NOT NULL COMMENT '主题/活动 id；原行快照',
  topic_id BIGINT NULL COMMENT '活动票解析出的主题；独立活动可为 NULL',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0待领取 1已领取 2已撤销 3已过期 4已释放',
  token_nonce CHAR(16) NOT NULL COMMENT '公开随机数；用于幂等重放重算同一枚签名码',
  token_hash CHAR(64) NOT NULL COMMENT '邀请码 SHA-256；明文永不落库',
  expires_at DATETIME(3) NOT NULL COMMENT '服务端过期；受 TTL 与原票/活动结束时间约束',
  invitee_member_id BIGINT NULL COMMENT '领取人；未领取为 NULL',
  claimed_at DATETIME(3) NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  version INT NOT NULL DEFAULT 0 COMMENT 'CAS/审计版本',
  active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN status IN (0, 1) THEN 1 ELSE NULL END) STORED
    COMMENT '唯一键只压住正在占席的行；终态行可为 NULL 保留历史',
  PRIMARY KEY (id),
  UNIQUE KEY uk_seat_invitation_token (token_hash),
  UNIQUE KEY uk_seat_invitation_active (registration_id, seat_no, active_flag),
  KEY idx_seat_invitation_recipient (invitee_member_id, status),
  KEY idx_seat_invitation_owner (owner_member_id, registration_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P5 已付多人票内含席位邀请事实';

-- 预检：幂等回执表的形状；坏表同样失败关闭。
SET @seat_invitation_request_table_exists := (
    SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation_request');

SET @seat_invitation_request_shape_ok := (
    @seat_invitation_request_table_exists = 0
    OR (
        SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation_request'
           AND (
             (column_name = 'id' AND column_type = 'bigint' AND is_nullable = 'NO' AND extra = 'auto_increment') OR
             (column_name = 'request_id' AND column_type = 'varchar(64)' AND is_nullable = 'NO') OR
             (column_name = 'member_id' AND column_type = 'bigint' AND is_nullable = 'NO') OR
             (column_name = 'operation' AND column_type = 'varchar(16)' AND is_nullable = 'NO') OR
             (column_name = 'registration_id' AND column_type = 'bigint' AND is_nullable = 'NO') OR
             (column_name = 'invitation_id' AND column_type = 'bigint' AND is_nullable = 'NO') OR
             (column_name = 'request_fingerprint' AND column_type = 'char(64)' AND is_nullable = 'NO') OR
             (column_name = 'result_status' AND column_type = 'tinyint' AND is_nullable = 'NO') OR
             (column_name = 'create_time' AND column_type = 'datetime(3)' AND is_nullable = 'NO')
           )
    ) = 9
);
-- runner: assert=scalar-true
SELECT @seat_invitation_request_shape_ok AS cms_registration_seat_invitation_request_precheck;
SET @seat_invitation_request_guard_sql := IF(@seat_invitation_request_shape_ok,
    'SELECT 1', 'SELECT * FROM information_schema.__seat_invitation_request_precheck_failed__');
PREPARE seat_invitation_request_guard FROM @seat_invitation_request_guard_sql;
EXECUTE seat_invitation_request_guard;
DEALLOCATE PREPARE seat_invitation_request_guard;

CREATE TABLE IF NOT EXISTS cms_registration_seat_invitation_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_id VARCHAR(64) NOT NULL COMMENT '客户端请求号；同号同指纹幂等重放',
  member_id BIGINT NOT NULL COMMENT '发起人；幂等命名空间为 member_id + request_id，不同会员可用同号',
  operation VARCHAR(16) NOT NULL COMMENT 'INVITE/CLAIM/CANCEL/RELEASE',
  registration_id BIGINT NOT NULL,
  invitation_id BIGINT NOT NULL,
  request_fingerprint CHAR(64) NOT NULL COMMENT '服务端请求指纹；同号不同指纹=409',
  result_status TINYINT NOT NULL COMMENT '操作完成后的邀请状态',
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_seat_invitation_request (member_id, request_id),
  KEY idx_seat_invitation_request_invitation (invitation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P5 席位邀请幂等回执';

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation'
      AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation') = 18
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation'
      AND index_name = 'uk_seat_invitation_token' AND non_unique = 0) = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation'
      AND index_name = 'uk_seat_invitation_active' AND non_unique = 0
      AND ((seq_in_index = 1 AND column_name = 'registration_id')
        OR (seq_in_index = 2 AND column_name = 'seat_no')
        OR (seq_in_index = 3 AND column_name = 'active_flag'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation_request'
      AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation_request') = 9
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation_request'
      AND index_name = 'uk_seat_invitation_request' AND non_unique = 0
      AND ((seq_in_index = 1 AND column_name = 'member_id')
        OR (seq_in_index = 2 AND column_name = 'request_id'))) = 2
) AS registration_seat_invitation_ready;

-- 回退限制：已有邀请/领取事实后禁止盲删；需要回退时先导出并按售后流程处理。
-- DROP TABLE cms_registration_seat_invitation_request;
-- DROP TABLE cms_registration_seat_invitation;
