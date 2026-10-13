-- 商家 CRM Phase 1A 合规触达：追加式营销同意、服务端分群快照与逐人真实回执。
-- 阶段：pre-deploy schema；本文件只建表并回读结构，不注册依赖新 jar 的 Quartz 任务。
-- 前置：migration_business_audit_event_20260823.sql、
--       migration_merchant_operator_crm_phase1a_20260823.sql。
-- 资源表 merchant_id 始终为服务端解析的 mms_merchant.id；owner memberId 仅保留为来源/审计快照。

CREATE TABLE IF NOT EXISTS `merchant_crm_marketing_consent` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL COMMENT '明确授权或退订的玩家',
  `merchant_id` bigint NOT NULL COMMENT '商家主体 mms_merchant.member_id',
  `merchant_row_id` bigint NOT NULL COMMENT 'mms_merchant.id 审计快照',
  `channel` varchar(16) NOT NULL COMMENT 'IN_APP/COUPON',
  `event_type` varchar(16) NOT NULL COMMENT 'OPT_IN/OPT_OUT，追加事件',
  `request_id` varchar(64) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_crm_consent_request` (`member_id`, `merchant_row_id`, `request_id`),
  KEY `idx_merchant_crm_consent_latest` (`member_id`, `merchant_row_id`, `channel`, `id`),
  CONSTRAINT `chk_merchant_crm_consent_event` CHECK (`event_type` IN ('OPT_IN', 'OPT_OUT')),
  CONSTRAINT `chk_merchant_crm_consent_channel` CHECK (`channel` IN ('IN_APP', 'COUPON'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家对单一商家的营销同意追加事件';

CREATE TABLE IF NOT EXISTS `merchant_crm_campaign` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint NOT NULL COMMENT '门店主体 mms_merchant.id',
  `merchant_owner_member_id` bigint NOT NULL COMMENT 'mms_merchant.member_id，仅供来源查询与审计',
  `merchant_name_snapshot` varchar(100) NOT NULL,
  `segment_id` bigint NOT NULL COMMENT '服务端已保存分群',
  `segment_filter_json` json NOT NULL COMMENT '创建时白名单筛选快照',
  `channel` varchar(16) NOT NULL,
  `coupon_id` bigint DEFAULT NULL,
  `title` varchar(60) NOT NULL,
  `content` varchar(500) NOT NULL,
  `actor_member_id` bigint NOT NULL,
  `actor_role_code` varchar(32) NOT NULL,
  `request_id` varchar(64) NOT NULL,
  `status` varchar(24) NOT NULL COMMENT 'READY/PROCESSING/SUCCESS/PARTIAL_FAILED/NO_ELIGIBLE',
  `recipient_count` int NOT NULL DEFAULT 0,
  `delivered_count` int NOT NULL DEFAULT 0,
  `no_consent_count` int NOT NULL DEFAULT 0,
  `frequency_skipped_count` int NOT NULL DEFAULT 0,
  `failed_count` int NOT NULL DEFAULT 0,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `processing_token` varchar(64) DEFAULT NULL COMMENT 'PROCESSING 持有者的随机 fence token',
  `processing_lease_until` datetime(3) DEFAULT NULL COMMENT '卡滞任务可被 CAS 接管的时间',
  `version` bigint NOT NULL DEFAULT 0 COMMENT 'PROCESSING 接管版本',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_crm_campaign_request` (`merchant_id`, `request_id`),
  KEY `idx_merchant_crm_campaign_owner` (`merchant_id`, `id`),
  KEY `idx_merchant_crm_campaign_status` (`status`, `id`),
  CONSTRAINT `chk_merchant_crm_campaign_channel` CHECK (`channel` IN ('IN_APP', 'COUPON')),
  CONSTRAINT `chk_merchant_crm_campaign_status` CHECK (`status` IN ('READY', 'PROCESSING', 'SUCCESS', 'PARTIAL_FAILED', 'NO_ELIGIBLE')),
  CONSTRAINT `chk_merchant_crm_campaign_counts` CHECK (`recipient_count` BETWEEN 0 AND 200 AND `delivered_count` BETWEEN 0 AND 200 AND `no_consent_count` BETWEEN 0 AND 200 AND `frequency_skipped_count` BETWEEN 0 AND 200 AND `failed_count` BETWEEN 0 AND 200),
  CONSTRAINT `chk_merchant_crm_campaign_lease` CHECK ((`status` = 'PROCESSING' AND `processing_token` IS NOT NULL AND `processing_lease_until` IS NOT NULL) OR (`status` <> 'PROCESSING' AND `processing_token` IS NULL AND `processing_lease_until` IS NULL)),
  CONSTRAINT `chk_merchant_crm_campaign_version` CHECK (`version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家 CRM 合规触达任务';

-- 兼容已存在的旧 campaign 表：旧 merchant_id=owner memberId，merchant_row_id=mms_merchant.id。
-- MySQL 8 没有 ADD COLUMN IF NOT EXISTS；分两步幂等改名，任一步中断后重跑都能继续收敛。
SET @merchant_crm_campaign_legacy_owner_shape := (
  SELECT SUM(column_name = 'merchant_id' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO') = 1
     AND SUM(column_name = 'merchant_row_id' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO') = 1
     AND SUM(column_name = 'merchant_owner_member_id') = 0
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_campaign'
);
SET @merchant_crm_campaign_owner_upgrade_sql := IF(
  @merchant_crm_campaign_legacy_owner_shape,
  'ALTER TABLE `merchant_crm_campaign` CHANGE COLUMN `merchant_id` `merchant_owner_member_id` bigint NOT NULL COMMENT ''mms_merchant.member_id，仅供来源查询与审计''',
  'SELECT 1'
);
PREPARE merchant_crm_campaign_owner_upgrade_stmt FROM @merchant_crm_campaign_owner_upgrade_sql;
EXECUTE merchant_crm_campaign_owner_upgrade_stmt;
DEALLOCATE PREPARE merchant_crm_campaign_owner_upgrade_stmt;

SET @merchant_crm_campaign_legacy_row_shape := (
  SELECT SUM(column_name = 'merchant_id') = 0
     AND SUM(column_name = 'merchant_row_id' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO') = 1
     AND SUM(column_name = 'merchant_owner_member_id' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO') = 1
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_campaign'
);
SET @merchant_crm_campaign_row_upgrade_sql := IF(
  @merchant_crm_campaign_legacy_row_shape,
  'ALTER TABLE `merchant_crm_campaign`
     CHANGE COLUMN `merchant_row_id` `merchant_id` bigint NOT NULL COMMENT ''门店主体 mms_merchant.id'',
     DROP INDEX `uk_merchant_crm_campaign_request`,
     DROP INDEX `idx_merchant_crm_campaign_owner`,
     ADD UNIQUE KEY `uk_merchant_crm_campaign_request` (`merchant_id`, `request_id`),
     ADD KEY `idx_merchant_crm_campaign_owner` (`merchant_id`, `id`)',
  'SELECT 1'
);
PREPARE merchant_crm_campaign_row_upgrade_stmt FROM @merchant_crm_campaign_row_upgrade_sql;
EXECUTE merchant_crm_campaign_row_upgrade_stmt;
DEALLOCATE PREPARE merchant_crm_campaign_row_upgrade_stmt;

CREATE TABLE IF NOT EXISTS `merchant_crm_campaign_recipient` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `campaign_id` bigint NOT NULL,
  `merchant_id` bigint NOT NULL COMMENT '门店主体 mms_merchant.id',
  `member_id` bigint NOT NULL COMMENT '服务端分群重算出的玩家',
  `customer_name_snapshot` varchar(100) NOT NULL,
  `channel` varchar(16) NOT NULL,
  `consent_event_id_snapshot` bigint DEFAULT NULL COMMENT '创建时最新 OPT_IN；投递前仍会实时复核',
  `status` varchar(32) NOT NULL COMMENT 'PENDING/DELIVERED/SKIPPED_NO_CONSENT/SKIPPED_FREQUENCY/RETRYABLE_BUSY/FAILED',
  `attempt_count` int NOT NULL DEFAULT 0,
  `failure_code` varchar(40) DEFAULT NULL,
  `failure_message` varchar(120) DEFAULT NULL COMMENT '稳定错误，不存手机号或异常堆栈',
  `message_receipt_id` bigint DEFAULT NULL,
  `coupon_history_id` bigint DEFAULT NULL,
  `delivered_at` datetime DEFAULT NULL,
  `last_attempt_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_crm_campaign_recipient` (`campaign_id`, `member_id`, `channel`),
  KEY `idx_merchant_crm_recipient_owner` (`merchant_id`, `campaign_id`, `status`, `id`),
  KEY `idx_merchant_crm_recipient_frequency` (`merchant_id`, `member_id`, `channel`, `status`, `delivered_at`),
  CONSTRAINT `chk_merchant_crm_recipient_channel` CHECK (`channel` IN ('IN_APP', 'COUPON')),
  CONSTRAINT `chk_merchant_crm_recipient_status` CHECK (`status` IN ('PENDING', 'DELIVERED', 'SKIPPED_NO_CONSENT', 'SKIPPED_FREQUENCY', 'RETRYABLE_BUSY', 'FAILED')),
  CONSTRAINT `chk_merchant_crm_recipient_attempts` CHECK (`attempt_count` BETWEEN 0 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家 CRM 服务端冻结逐人触达回执';

-- 跨 campaign 的数据库日频控真源。先持久化 RESERVED，再单独提交 DISPATCHING，才允许调用外部渠道。
-- DISPATCHING 后即使结果未知也不自动重发：优先保证「最多一次」，接受极端崩溃窗口内可能少发一次。
CREATE TABLE IF NOT EXISTS `merchant_crm_delivery_guard` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint NOT NULL COMMENT '门店主体 mms_merchant.id',
  `member_id` bigint NOT NULL COMMENT '收件玩家',
  `channel` varchar(16) NOT NULL COMMENT 'IN_APP/COUPON',
  `delivery_date` date NOT NULL COMMENT 'MySQL CURRENT_DATE 生成的自然日',
  `campaign_id` bigint NOT NULL,
  `recipient_id` bigint NOT NULL,
  `status` varchar(24) NOT NULL COMMENT 'RESERVED/DISPATCHING/DELIVERED/FAILED_UNCERTAIN',
  `lease_token` varchar(64) DEFAULT NULL COMMENT 'guard CAS token',
  `lease_until` datetime(3) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT 0,
  `message_receipt_id` bigint DEFAULT NULL,
  `coupon_history_id` bigint DEFAULT NULL,
  `failure_code` varchar(40) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_crm_delivery_daily` (`merchant_id`, `member_id`, `channel`, `delivery_date`),
  UNIQUE KEY `uk_merchant_crm_delivery_recipient` (`campaign_id`, `recipient_id`),
  KEY `idx_merchant_crm_delivery_stale` (`status`, `lease_until`, `id`),
  CONSTRAINT `chk_merchant_crm_delivery_channel` CHECK (`channel` IN ('IN_APP', 'COUPON')),
  CONSTRAINT `chk_merchant_crm_delivery_status` CHECK (`status` IN ('RESERVED', 'DISPATCHING', 'DELIVERED', 'FAILED_UNCERTAIN')),
  CONSTRAINT `chk_merchant_crm_delivery_lease` CHECK (((`status` = 'RESERVED' OR `status` = 'DISPATCHING') AND `lease_token` IS NOT NULL AND `lease_until` IS NOT NULL) OR ((`status` = 'DELIVERED' OR `status` = 'FAILED_UNCERTAIN') AND `lease_token` IS NULL AND `lease_until` IS NULL)),
  CONSTRAINT `chk_merchant_crm_delivery_version` CHECK (`version` >= 0),
  CONSTRAINT `chk_merchant_crm_delivery_receipt` CHECK (`status` <> 'DELIVERED' OR (`channel` = 'IN_APP' AND `message_receipt_id` IS NOT NULL AND `coupon_history_id` IS NULL) OR (`channel` = 'COUPON' AND `message_receipt_id` IS NULL AND `coupon_history_id` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家 CRM 跨任务自然日投递互斥与回执';

-- 旧 recipient/guard 的 merchant_id=owner memberId；campaign 的 row 快照是唯一可信门店来源。
-- 先拒绝 orphan、非法 namespace 与 projected 日频键冲突，再做可重跑的逐行收敛。
-- runner: assert=scalar-true
SELECT NOT EXISTS (
         SELECT 1
         FROM merchant_crm_campaign_recipient recipient
         LEFT JOIN merchant_crm_campaign campaign ON campaign.id = recipient.campaign_id
         WHERE campaign.id IS NULL
            OR recipient.merchant_id NOT IN
               (campaign.merchant_owner_member_id, campaign.merchant_id)
       )
   AND NOT EXISTS (
         SELECT 1
         FROM merchant_crm_delivery_guard guard_row
         LEFT JOIN merchant_crm_campaign campaign ON campaign.id = guard_row.campaign_id
         LEFT JOIN merchant_crm_campaign_recipient recipient
           ON recipient.id = guard_row.recipient_id
         WHERE campaign.id IS NULL OR recipient.id IS NULL
            OR recipient.campaign_id <> guard_row.campaign_id
            OR recipient.member_id <> guard_row.member_id
            OR recipient.channel <> guard_row.channel
            OR guard_row.merchant_id NOT IN
               (campaign.merchant_owner_member_id, campaign.merchant_id)
       )
   AND NOT EXISTS (
         SELECT 1
         FROM (
           SELECT campaign.merchant_id, guard_row.member_id, guard_row.channel,
                  guard_row.delivery_date, COUNT(*) AS projected_count
           FROM merchant_crm_delivery_guard guard_row
           JOIN merchant_crm_campaign campaign ON campaign.id = guard_row.campaign_id
           GROUP BY campaign.merchant_id, guard_row.member_id,
                    guard_row.channel, guard_row.delivery_date
           HAVING COUNT(*) > 1
         ) projected_guard_collision
       ) AS assertion_ok;

UPDATE `merchant_crm_campaign_recipient`
SET `merchant_id` = (
  SELECT campaign.merchant_id
  FROM merchant_crm_campaign campaign
  WHERE campaign.id = merchant_crm_campaign_recipient.campaign_id
)
WHERE `merchant_id` <> (
        SELECT campaign.merchant_id
        FROM merchant_crm_campaign campaign
        WHERE campaign.id = merchant_crm_campaign_recipient.campaign_id
      )
  AND EXISTS (
        SELECT 1 FROM merchant_crm_campaign campaign
        WHERE campaign.id = merchant_crm_campaign_recipient.campaign_id
          AND merchant_crm_campaign_recipient.merchant_id = campaign.merchant_owner_member_id
      );

UPDATE `merchant_crm_delivery_guard`
SET `merchant_id` = (
  SELECT campaign.merchant_id
  FROM merchant_crm_campaign campaign
  WHERE campaign.id = merchant_crm_delivery_guard.campaign_id
)
WHERE `merchant_id` <> (
        SELECT campaign.merchant_id
        FROM merchant_crm_campaign campaign
        WHERE campaign.id = merchant_crm_delivery_guard.campaign_id
      )
  AND EXISTS (
        SELECT 1 FROM merchant_crm_campaign campaign
        WHERE campaign.id = merchant_crm_delivery_guard.campaign_id
          AND merchant_crm_delivery_guard.merchant_id = campaign.merchant_owner_member_id
      );

-- runner: assert=scalar-true
SELECT NOT EXISTS (
         SELECT 1
         FROM merchant_crm_campaign_recipient recipient
         LEFT JOIN merchant_crm_campaign campaign ON campaign.id = recipient.campaign_id
         WHERE campaign.id IS NULL OR recipient.merchant_id <> campaign.merchant_id
       )
   AND NOT EXISTS (
         SELECT 1
         FROM merchant_crm_delivery_guard guard_row
         LEFT JOIN merchant_crm_campaign campaign ON campaign.id = guard_row.campaign_id
         LEFT JOIN merchant_crm_campaign_recipient recipient
           ON recipient.id = guard_row.recipient_id
         WHERE campaign.id IS NULL OR recipient.id IS NULL
            OR recipient.campaign_id <> guard_row.campaign_id
            OR recipient.member_id <> guard_row.member_id
            OR recipient.channel <> guard_row.channel
            OR recipient.merchant_id <> campaign.merchant_id
            OR guard_row.merchant_id <> campaign.merchant_id
       ) AS assertion_ok;

-- 四张迁移表必须都由 InnoDB 承载；仅按表名计数会漏掉同名 MyISAM 表。
-- runner: assert=scalar-true
SELECT COUNT(*) = 4
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('merchant_crm_marketing_consent', 'merchant_crm_campaign', 'merchant_crm_campaign_recipient', 'merchant_crm_delivery_guard')
  AND UPPER(engine) = 'INNODB';

-- campaign 的门店/owner/status/token/lease/version 与 guard 的门店/member/channel/status/version/lease 精确列形。
-- runner: assert=scalar-true
SELECT COUNT(*) = 12
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND ((table_name = 'merchant_crm_campaign' AND column_name = 'merchant_id' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO')
    OR (table_name = 'merchant_crm_campaign' AND column_name = 'merchant_owner_member_id' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO')
    OR (table_name = 'merchant_crm_campaign' AND column_name = 'status' AND LOWER(data_type) = 'varchar' AND is_nullable = 'NO')
    OR (table_name = 'merchant_crm_campaign' AND column_name = 'processing_token' AND LOWER(data_type) = 'varchar' AND is_nullable = 'YES')
    OR (table_name = 'merchant_crm_campaign' AND column_name = 'processing_lease_until' AND LOWER(data_type) = 'datetime' AND datetime_precision = 3 AND is_nullable = 'YES')
    OR (table_name = 'merchant_crm_campaign' AND column_name = 'version' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO')
    OR (table_name = 'merchant_crm_delivery_guard' AND column_name = 'merchant_id' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO')
    OR (table_name = 'merchant_crm_delivery_guard' AND column_name = 'member_id' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO')
    OR (table_name = 'merchant_crm_delivery_guard' AND column_name = 'channel' AND LOWER(data_type) = 'varchar' AND is_nullable = 'NO')
    OR (table_name = 'merchant_crm_delivery_guard' AND column_name = 'delivery_date' AND LOWER(data_type) = 'date' AND is_nullable = 'NO')
    OR (table_name = 'merchant_crm_delivery_guard' AND column_name = 'status' AND LOWER(data_type) = 'varchar' AND is_nullable = 'NO')
    OR (table_name = 'merchant_crm_delivery_guard' AND column_name = 'version' AND LOWER(data_type) = 'bigint' AND is_nullable = 'NO'));

-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_campaign'
  AND column_name = 'merchant_row_id';

-- 租约列单独钉精度；少任一列都必须 fail closed。
-- runner: assert=scalar-true
SELECT COUNT(*) = 2
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_delivery_guard'
  AND ((column_name = 'lease_token' AND LOWER(data_type) = 'varchar' AND is_nullable = 'YES')
    OR (column_name = 'lease_until' AND LOWER(data_type) = 'datetime' AND datetime_precision = 3 AND is_nullable = 'YES'));

-- 所有幂等 / 日频控 UNIQUE 必须逐位精确匹配，不能只认索引名。
-- runner: assert=scalar-true
SELECT COUNT(*) = 5
FROM (
  SELECT table_name, index_name
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND ((table_name = 'merchant_crm_marketing_consent' AND index_name = 'uk_merchant_crm_consent_request')
      OR (table_name = 'merchant_crm_campaign' AND index_name = 'uk_merchant_crm_campaign_request')
      OR (table_name = 'merchant_crm_campaign_recipient' AND index_name = 'uk_merchant_crm_campaign_recipient')
      OR (table_name = 'merchant_crm_delivery_guard' AND index_name = 'uk_merchant_crm_delivery_daily')
      OR (table_name = 'merchant_crm_delivery_guard' AND index_name = 'uk_merchant_crm_delivery_recipient'))
  GROUP BY table_name, index_name
  HAVING MIN(non_unique) = 0 AND MAX(non_unique) = 0
     AND MIN(index_type) = 'BTREE' AND MAX(index_type) = 'BTREE'
     AND COUNT(sub_part) = 0 AND MIN(is_visible) = 'YES' AND MAX(is_visible) = 'YES'
     AND ((table_name = 'merchant_crm_marketing_consent' AND COUNT(*) = 3
           AND SUM(seq_in_index = 1 AND column_name = 'member_id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'merchant_row_id') = 1
           AND SUM(seq_in_index = 3 AND column_name = 'request_id') = 1)
       OR (table_name = 'merchant_crm_campaign' AND COUNT(*) = 2
           AND SUM(seq_in_index = 1 AND column_name = 'merchant_id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'request_id') = 1)
       OR (table_name = 'merchant_crm_campaign_recipient' AND COUNT(*) = 3
           AND SUM(seq_in_index = 1 AND column_name = 'campaign_id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'member_id') = 1
           AND SUM(seq_in_index = 3 AND column_name = 'channel') = 1)
       OR (table_name = 'merchant_crm_delivery_guard' AND index_name = 'uk_merchant_crm_delivery_daily' AND COUNT(*) = 4
           AND SUM(seq_in_index = 1 AND column_name = 'merchant_id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'member_id') = 1
           AND SUM(seq_in_index = 3 AND column_name = 'channel') = 1
           AND SUM(seq_in_index = 4 AND column_name = 'delivery_date') = 1)
       OR (table_name = 'merchant_crm_delivery_guard' AND index_name = 'uk_merchant_crm_delivery_recipient' AND COUNT(*) = 2
           AND SUM(seq_in_index = 1 AND column_name = 'campaign_id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'recipient_id') = 1))
) exact_unique_keys;

-- 四张表只能有下方逐条列出的 15 个 CHECK；先钉总数，避免加了未审计约束仍假绿。
-- runner: assert=scalar-true
SELECT COUNT(*) = 15
FROM information_schema.table_constraints tc
WHERE tc.table_schema = DATABASE()
  AND tc.constraint_schema = DATABASE()
  AND tc.constraint_type = 'CHECK'
  AND tc.table_name IN (
    'merchant_crm_marketing_consent',
    'merchant_crm_campaign',
    'merchant_crm_campaign_recipient',
    'merchant_crm_delivery_guard'
  );

-- 12 个简单 CHECK 必须绑定到目标表、完整表达式且处于 ENFORCED；
-- 只查 check_constraints 会把同名错表或 NOT ENFORCED 约束误判成已生效。
-- runner: assert=scalar-true
SELECT COUNT(*) = 12
FROM information_schema.table_constraints tc
JOIN information_schema.check_constraints cc
  ON cc.constraint_catalog = tc.constraint_catalog
 AND cc.constraint_schema = tc.constraint_schema
 AND cc.constraint_name = tc.constraint_name
WHERE tc.table_schema = DATABASE()
  AND tc.constraint_schema = DATABASE()
  AND tc.constraint_type = 'CHECK'
  AND tc.enforced = 'YES'
  AND ((tc.table_name = 'merchant_crm_marketing_consent'
        AND tc.constraint_name = 'chk_merchant_crm_consent_event'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = '75938ee1f39c3801eff910a3f4e68ed816f6ddf4a720c38bb7850542ba0b5a65')
    OR (tc.table_name = 'merchant_crm_marketing_consent'
        AND tc.constraint_name = 'chk_merchant_crm_consent_channel'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = 'd0a50e1594e3992a4f7c29d4a288814f62e50e2f1b055a84245f32e6a5ee818b')
    OR (tc.table_name = 'merchant_crm_campaign'
        AND tc.constraint_name = 'chk_merchant_crm_campaign_channel'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = 'd0a50e1594e3992a4f7c29d4a288814f62e50e2f1b055a84245f32e6a5ee818b')
    OR (tc.table_name = 'merchant_crm_campaign'
        AND tc.constraint_name = 'chk_merchant_crm_campaign_status'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = 'd7828e80bf354d77befc4ffdb5ad6526e215687bf85b2c529193a7d218e70f62')
    OR (tc.table_name = 'merchant_crm_campaign'
        AND tc.constraint_name = 'chk_merchant_crm_campaign_counts'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = 'b3fc86ae21d08c5999ac2a8d300b568f9893450d6ddf2fbf248182a10c51362b')
    OR (tc.table_name = 'merchant_crm_campaign'
        AND tc.constraint_name = 'chk_merchant_crm_campaign_version'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = 'ed78cf3fd4c0f383a408b73d34cba988c4fd704986f49efe37d83f0b8e932379')
    OR (tc.table_name = 'merchant_crm_campaign_recipient'
        AND tc.constraint_name = 'chk_merchant_crm_recipient_channel'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = 'd0a50e1594e3992a4f7c29d4a288814f62e50e2f1b055a84245f32e6a5ee818b')
    OR (tc.table_name = 'merchant_crm_campaign_recipient'
        AND tc.constraint_name = 'chk_merchant_crm_recipient_status'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = '29b45aca49e7cf14e3f4a440ba6a69c661c98406d237adeb24961d4ca60182f2')
    OR (tc.table_name = 'merchant_crm_campaign_recipient'
        AND tc.constraint_name = 'chk_merchant_crm_recipient_attempts'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = '96a885b632ed50eaa794aefc08852dc4f954aa558f19d27ac6c87ebb680c9d57')
    OR (tc.table_name = 'merchant_crm_delivery_guard'
        AND tc.constraint_name = 'chk_merchant_crm_delivery_status'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = 'e17e83edec2fcd96aae69568b9b89d77ee0176cd4107644980feb5d7495cde92')
    OR (tc.table_name = 'merchant_crm_delivery_guard'
        AND tc.constraint_name = 'chk_merchant_crm_delivery_channel'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = 'd0a50e1594e3992a4f7c29d4a288814f62e50e2f1b055a84245f32e6a5ee818b')
    OR (tc.table_name = 'merchant_crm_delivery_guard'
        AND tc.constraint_name = 'chk_merchant_crm_delivery_version'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = 'ed78cf3fd4c0f383a408b73d34cba988c4fd704986f49efe37d83f0b8e932379'));

-- lease / receipt 复合 CHECK 也逐字符精确比较；只认同名约束挡不住反义或缺条件表达式。
-- runner: assert=scalar-true
SELECT COUNT(*) = 3
FROM information_schema.table_constraints tc
JOIN information_schema.check_constraints cc
  ON cc.constraint_catalog = tc.constraint_catalog
 AND cc.constraint_schema = tc.constraint_schema
 AND cc.constraint_name = tc.constraint_name
WHERE tc.table_schema = DATABASE()
  AND tc.constraint_schema = DATABASE()
  AND tc.constraint_type = 'CHECK'
  AND tc.enforced = 'YES'
  AND ((tc.table_name = 'merchant_crm_campaign'
        AND tc.constraint_name = 'chk_merchant_crm_campaign_lease'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = '7be8970f09a57e608c4c8f30c87b127ad7a6ab68f34ccdd61ec775c0164f7552')
    OR (tc.table_name = 'merchant_crm_delivery_guard'
        AND tc.constraint_name = 'chk_merchant_crm_delivery_lease'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = '857486327e61eb81d15aefb2e67731d8ea49573a66c11207f15cc344cc2fd89d')
    OR (tc.table_name = 'merchant_crm_delivery_guard'
        AND tc.constraint_name = 'chk_merchant_crm_delivery_receipt'
        AND SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
            '[[:space:]`\\\\]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256)
            = 'c73e8e49e70877a156e330d8d17a3b0a3c6f915f264e621d61b909c23c4f566f'));
