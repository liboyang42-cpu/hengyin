-- 营销推广归因（CO-09 归因半边）。只新增两张表，不引用任何金额/费率/支付状态列。
-- Source-only ordered migration. 不执行生产 DDL；须走 chengyin-db-migrate 只读预检并由 root 决定落库时点。
-- 依赖：无（未引用既有业务表；credential_id 只做本域内 FK）。
-- runner: assert=scalar-true
SELECT CAST(IF(DATABASE() IS NOT NULL
  AND CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)=8
  AND (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)>0
    OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=16)
  AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@sql_mode)>0)
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_referral_credential','marketing_attribution'))=0
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND constraint_name IN ('uk_referral_public_id','uk_referral_token_hash','uk_attribution_order',
      'fk_attribution_credential','ck_referral_target_type','ck_referral_status','ck_referral_window'))=0,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_attribution_preflight','$')) AS UNSIGNED) AS assertion_ok;

CREATE TABLE IF NOT EXISTS marketing_referral_credential (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(32) NOT NULL COMMENT '公开标识(HTTP 可见,非秘密),与签名共同确定凭据身份',
    sharer_member_id BIGINT NOT NULL COMMENT '签发人/分享者会员 id',
    campaign_key VARCHAR(40) NOT NULL COMMENT '推广活动标识,仅分组统计',
    target_type TINYINT NOT NULL COMMENT '1=主题 2=活动,与 cms_registration.owner_type 对齐',
    target_id BIGINT NOT NULL,
    issued_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=ACTIVE 1=REVOKED',
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256(明文 token);明文不落库',
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_referral_public_id (public_id),
    UNIQUE KEY uk_referral_token_hash (token_hash),
    KEY idx_referral_sharer (sharer_member_id, issued_at),
    CONSTRAINT ck_referral_target_type CHECK (target_type IN (1,2)),
    CONSTRAINT ck_referral_status CHECK (status IN (0,1)),
    CONSTRAINT ck_referral_window CHECK (expires_at > issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推广来源凭据:只证明"谁推广了什么、有效到何时",不含任何金额/佣金字段';

CREATE TABLE IF NOT EXISTS marketing_attribution (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_type TINYINT NOT NULL COMMENT '1=cms_registration(v1 仅此一种)',
    order_id BIGINT NOT NULL,
    credential_id BIGINT UNSIGNED NOT NULL,
    sharer_member_id BIGINT NOT NULL COMMENT '写入时从凭据冗余,便于按人统计',
    campaign_key VARCHAR(40) NOT NULL,
    claimed_member_id BIGINT NOT NULL COMMENT '实际下单人,必须等于订单 member_id',
    order_created_at DATETIME NOT NULL COMMENT '下单时刻快照(订单 create_time),与凭据有效期共同构成归因窗口',
    snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范字段摘要,发现事后篡改',
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attribution_order (order_type, order_id),
    KEY idx_attribution_credential (credential_id),
    KEY idx_attribution_sharer (sharer_member_id, create_time),
    CONSTRAINT fk_attribution_credential FOREIGN KEY (credential_id)
        REFERENCES marketing_referral_credential (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='下单来源快照:insert-only,一单一行,同单换来源由唯一键拒绝';

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_referral_credential','marketing_attribution')
    AND UPPER(engine)='INNODB')=2
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND index_name IN ('uk_referral_public_id','uk_referral_token_hash','uk_attribution_order')
    AND non_unique=0)>=3
  AND (SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE()
    AND constraint_name='fk_attribution_credential'
    AND column_name='credential_id' AND referenced_table_name='marketing_referral_credential'
    AND referenced_column_name='id')=1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='marketing_attribution'
    AND column_name IN ('amount','fee_rate','payment_status','settlement_id'))=0,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_attribution_readback','$')) AS UNSIGNED) AS assertion_ok;
