-- 商家定向广播:客户页「当前筛选」一次性通知(拍板 2026-09-16 第 15 条)。
-- 语义:商家按当前筛选给自有客户发一条站内通知,只发「已同意该商家站内触达」的客户;
--       频次 = 同一商家每天最多 3 条(常量,见 MerchantCrmBroadcastServiceImpl)、同一客户每天最多收 1 条。
-- 日频控真源:复用 merchant_crm_delivery_guard 的自然日唯一键(merchant_id, member_id, channel, delivery_date),
--       与既有 CRM 触达任务共用一个「每人每天每商家每渠道 1 条」额度,不另立一套口径。
-- 说明:本文件只建表 + 回读,不落库(迁移由用户在合并前执行)。
-- 回滚: DROP TABLE merchant_crm_broadcast_recipient; DROP TABLE merchant_crm_broadcast;

CREATE TABLE IF NOT EXISTS `merchant_crm_broadcast` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint NOT NULL COMMENT '门店主体 mms_merchant.id',
  `merchant_owner_member_id` bigint NOT NULL COMMENT 'mms_merchant.member_id,来源查询与审计快照',
  `merchant_name_snapshot` varchar(100) NOT NULL,
  `actor_member_id` bigint NOT NULL COMMENT '真实操作员工/店主',
  `actor_role_code` varchar(32) NOT NULL,
  `request_id` varchar(64) NOT NULL,
  `broadcast_date` date NOT NULL COMMENT 'MySQL CURRENT_DATE 生成的自然日',
  `daily_slot` tinyint NOT NULL COMMENT '商家当日第几条(1..3);唯一键兜底「每天最多 3 条」',
  `filter_json` json NOT NULL COMMENT '创建时的筛选快照(含 scope/groups)',
  `content` varchar(120) NOT NULL,
  `status` varchar(16) NOT NULL COMMENT 'SENDING/SUCCESS/PARTIAL_FAILED/FAILED',
  `audience_count` int NOT NULL DEFAULT 0 COMMENT '本批冻结名单人数(≤200)',
  `consented_count` int NOT NULL DEFAULT 0,
  `no_consent_count` int NOT NULL DEFAULT 0,
  `frequency_skipped_count` int NOT NULL DEFAULT 0,
  `delivered_count` int NOT NULL DEFAULT 0,
  `failed_count` int NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_crm_broadcast_request` (`merchant_id`, `request_id`),
  UNIQUE KEY `uk_merchant_crm_broadcast_daily` (`merchant_id`, `broadcast_date`, `daily_slot`),
  KEY `idx_merchant_crm_broadcast_owner` (`merchant_id`, `id`),
  CONSTRAINT `chk_merchant_crm_broadcast_status` CHECK (`status` IN ('SENDING', 'SUCCESS', 'PARTIAL_FAILED', 'FAILED')),
  CONSTRAINT `chk_merchant_crm_broadcast_slot` CHECK (`daily_slot` BETWEEN 1 AND 3),
  CONSTRAINT `chk_merchant_crm_broadcast_counts` CHECK (`audience_count` BETWEEN 0 AND 200 AND `consented_count` BETWEEN 0 AND 200
    AND `no_consent_count` BETWEEN 0 AND 200 AND `frequency_skipped_count` BETWEEN 0 AND 200
    AND `delivered_count` BETWEEN 0 AND 200 AND `failed_count` BETWEEN 0 AND 200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家定向广播任务';

CREATE TABLE IF NOT EXISTS `merchant_crm_broadcast_recipient` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `broadcast_id` bigint NOT NULL,
  `merchant_id` bigint NOT NULL COMMENT '门店主体 mms_merchant.id',
  `member_id` bigint NOT NULL COMMENT '服务端筛选重算出的玩家',
  `customer_name_snapshot` varchar(100) NOT NULL,
  `consent_event_id_snapshot` bigint DEFAULT NULL COMMENT '冻结时最新 OPT_IN;投递前仍会实时复核',
  `status` varchar(24) NOT NULL COMMENT 'PENDING/DELIVERED/SKIPPED_NO_CONSENT/SKIPPED_FREQUENCY/FAILED',
  `failure_code` varchar(40) DEFAULT NULL,
  `failure_message` varchar(120) DEFAULT NULL COMMENT '稳定错误文案,不存异常堆栈',
  `message_receipt_id` bigint DEFAULT NULL COMMENT '站内信真实回执',
  `attempt_count` int NOT NULL DEFAULT 0,
  `delivered_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_crm_broadcast_recipient` (`broadcast_id`, `member_id`),
  KEY `idx_merchant_crm_broadcast_recipient_owner` (`merchant_id`, `broadcast_id`, `status`, `id`),
  CONSTRAINT `chk_merchant_crm_broadcast_recipient_status` CHECK (`status` IN
    ('PENDING', 'DELIVERED', 'SKIPPED_NO_CONSENT', 'SKIPPED_FREQUENCY', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家定向广播冻结名单与逐人回执';

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND engine = 'InnoDB'
     AND table_name IN ('merchant_crm_broadcast','merchant_crm_broadcast_recipient')) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND
        ((table_name = 'merchant_crm_broadcast' AND column_name IN
          ('merchant_id','merchant_owner_member_id','request_id','broadcast_date','daily_slot',
           'filter_json','content','status','audience_count','consented_count','no_consent_count',
           'frequency_skipped_count','delivered_count','failed_count'))
         OR (table_name = 'merchant_crm_broadcast_recipient' AND column_name IN
          ('broadcast_id','merchant_id','member_id','customer_name_snapshot','consent_event_id_snapshot',
           'status','failure_code','failure_message','message_receipt_id','attempt_count','delivered_at')))) = 25
  AND (SELECT COUNT(*) FROM (
        SELECT table_name, index_name, MIN(non_unique) AS non_unique,
               GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name IN ('merchant_crm_broadcast','merchant_crm_broadcast_recipient')
        GROUP BY table_name, index_name
      ) exact_unique
      WHERE non_unique = 0 AND
        ((table_name = 'merchant_crm_broadcast' AND index_name = 'uk_merchant_crm_broadcast_request'
          AND cols = 'merchant_id,request_id')
         OR (table_name = 'merchant_crm_broadcast' AND index_name = 'uk_merchant_crm_broadcast_daily'
          AND cols = 'merchant_id,broadcast_date,daily_slot')
         OR (table_name = 'merchant_crm_broadcast_recipient' AND index_name = 'uk_merchant_crm_broadcast_recipient'
          AND cols = 'broadcast_id,member_id'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
         AND constraint_name IN ('chk_merchant_crm_broadcast_status','chk_merchant_crm_broadcast_slot',
           'chk_merchant_crm_broadcast_counts','chk_merchant_crm_broadcast_recipient_status')) = 4
  AS assertion_ok;
