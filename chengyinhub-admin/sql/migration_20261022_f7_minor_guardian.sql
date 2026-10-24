-- DOC2 F7 未成年模式：监护授权。只新建一张表，不改既有表、不回填数据。
--
-- 一条授权 = 被监护人(ward) × 监护人(guardian) × 用途(purpose) × 政策版本(policy_version) × 有效期(valid_from..valid_until)。
--   · purpose 封闭取值：PURCHASE（购买）/ INFO_PROCESS（个人信息处理）/ SOLO_DAYTIME_PLAY（16-17 岁白天低风险线路单独参与）。
--     PURCHASE 与 INFO_PROCESS 各自独立成行，互不代替。
--   · 证明方式只有「监护人本人账号发起 + 被监护人本人账号确认」双方登录确认（evidence_method），
--     短信验证码本身不构成监护证明，本表不收手机号、验证码。
--   · 不存身份证原件/号码、不存人脸等生物特征；年龄只取 ums_member.birthday。
--   · 邀请码只存 SHA-256 摘要（invite_token_hash），明文仅在创建时回给监护人一次。
--
-- 【前置】无。【落库顺序】先手工执行本迁移，再部署新 jar；feature.flag.minorMode 缺省关闭。
-- 幂等：表不存在则建；已存在则核对列 fingerprint，不一致时断言失败，不改不删。

CREATE TABLE IF NOT EXISTS minor_guardian_authorization (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  guardian_member_id BIGINT NOT NULL COMMENT '监护人会员ID（发起方）',
  ward_member_id BIGINT NULL COMMENT '被监护人会员ID（确认后写入）',
  purpose VARCHAR(24) NOT NULL COMMENT 'PURCHASE / INFO_PROCESS / SOLO_DAYTIME_PLAY',
  policy_version VARCHAR(32) NOT NULL COMMENT '同意时的未成年人保护政策版本',
  relation VARCHAR(16) NOT NULL COMMENT 'PARENT 父母 / GUARDIAN 其他法定监护人（自述）',
  evidence_method VARCHAR(32) NOT NULL DEFAULT 'MUTUAL_ACCOUNT_CONFIRM' COMMENT '证明方式：双方本人账号确认',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/REVOKED/SUPERSEDED',
  request_id VARCHAR(64) NOT NULL COMMENT '监护人侧幂等键',
  invite_token_hash CHAR(64) NULL COMMENT '邀请码 SHA-256（确认后清空）',
  invite_expires_at DATETIME NULL COMMENT '邀请码过期时间',
  valid_days INT NOT NULL COMMENT '监护人选择的有效天数',
  valid_from DATETIME NULL COMMENT '生效时间（被监护人确认时）',
  valid_until DATETIME NULL COMMENT '失效时间（不晚于被监护人满 18 周岁）',
  ward_confirmed_at DATETIME NULL COMMENT '被监护人确认时间',
  revoked_at DATETIME NULL COMMENT '撤回时间',
  revoked_by BIGINT NULL COMMENT '撤回人会员ID（监护人或被监护人）',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '修改时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_minor_guardian_request (guardian_member_id, request_id),
  UNIQUE KEY uk_minor_guardian_invite (invite_token_hash),
  KEY idx_minor_guardian_ward (ward_member_id, purpose, status),
  KEY idx_minor_guardian_guardian (guardian_member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='未成年人监护授权（用途/政策版本/有效期绑定）';

-- runner: assert=scalar-true
SELECT
  (SELECT GROUP_CONCAT(CONCAT_WS(':', column_name, column_type, is_nullable) ORDER BY ordinal_position SEPARATOR '|')
     FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='minor_guardian_authorization')
  = CONCAT('id:bigint:NO|guardian_member_id:bigint:NO|ward_member_id:bigint:YES|purpose:varchar(24):NO',
           '|policy_version:varchar(32):NO|relation:varchar(16):NO|evidence_method:varchar(32):NO',
           '|status:varchar(16):NO|request_id:varchar(64):NO|invite_token_hash:char(64):YES',
           '|invite_expires_at:datetime:YES|valid_days:int:NO|valid_from:datetime:YES|valid_until:datetime:YES',
           '|ward_confirmed_at:datetime:YES|revoked_at:datetime:YES|revoked_by:bigint:YES',
           '|create_time:datetime:NO|update_time:datetime:YES')
  AND
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='minor_guardian_authorization' AND index_name='uk_minor_guardian_request' AND non_unique=0) = 2
  AND
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='minor_guardian_authorization' AND index_name='uk_minor_guardian_invite' AND non_unique=0) = 1
  AS assertion_ok;
