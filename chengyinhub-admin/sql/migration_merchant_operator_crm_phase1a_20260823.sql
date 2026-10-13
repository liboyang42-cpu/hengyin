-- 商家 CRM Phase 1A：白名单保存分群 + 异步脱敏 Excel 导出任务。
-- 前置：migration_merchant_operator_crm_followup_20260823.sql、
--       migration_business_audit_event_20260823.sql。
-- merchant_id 始终冻结为服务端解析的 mms_merchant.id；owner memberId 只做来源查询/审计快照。

CREATE TABLE IF NOT EXISTS `merchant_customer_segment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint NOT NULL COMMENT '门店主体 mms_merchant.id',
  `name` varchar(30) NOT NULL,
  `filter_json` json NOT NULL COMMENT '仅复购/沉睡/待核销/退款/来源时间窗/tag白名单',
  `actor_member_id` bigint NOT NULL,
  `request_id` varchar(64) NOT NULL,
  `del_flag` tinyint NOT NULL DEFAULT 0,
  `version` int NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_customer_segment_request` (`merchant_id`, `request_id`),
  UNIQUE KEY `uk_merchant_customer_segment_name` (`merchant_id`, `name`, `del_flag`),
  KEY `idx_merchant_customer_segment_list` (`merchant_id`, `del_flag`, `update_time`, `id`),
  CONSTRAINT `chk_merchant_customer_segment_del` CHECK (`del_flag` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家CRM保存分群';

CREATE TABLE IF NOT EXISTS `merchant_crm_export_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint NOT NULL COMMENT '门店主体 mms_merchant.id',
  `merchant_owner_member_id` bigint NOT NULL COMMENT 'mms_merchant.member_id，仅供来源查询与审计',
  `requester_member_id` bigint NOT NULL COMMENT '真实操作员工/店主',
  `requester_role_code` varchar(32) NOT NULL,
  `filter_json` json NOT NULL COMMENT '服务端白名单归一化筛选快照',
  `include_paid_amount` tinyint NOT NULL DEFAULT 0 COMMENT '创建时是否持有CRM_SENSITIVE_READ',
  `status` varchar(16) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/EXPIRED',
  `version` int NOT NULL DEFAULT 0 COMMENT '状态CAS版本',
  `row_limit` int NOT NULL DEFAULT 5000,
  `row_count` int NOT NULL DEFAULT 0,
  `storage_key` varchar(64) DEFAULT NULL COMMENT '私有目录随机文件键，非公开URL',
  `token_hash` char(64) NOT NULL COMMENT '短时随机token的SHA-256；绝不存明文',
  `request_id` varchar(64) NOT NULL,
  `error_message` varchar(200) DEFAULT NULL COMMENT '稳定错误文案，不存异常/路径/敏感数据',
  `expires_at` datetime NOT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `downloaded_at` datetime DEFAULT NULL,
  `download_count` int NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_crm_export_request` (`merchant_id`, `request_id`),
  KEY `idx_merchant_crm_export_owner` (`merchant_id`, `id`),
  KEY `idx_merchant_crm_export_runner` (`status`, `expires_at`, `create_time`, `id`),
  CONSTRAINT `chk_merchant_crm_export_paid` CHECK (`include_paid_amount` IN (0, 1)),
  CONSTRAINT `chk_merchant_crm_export_status` CHECK (`status` IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'EXPIRED')),
  CONSTRAINT `chk_merchant_crm_export_limit` CHECK (`row_limit` BETWEEN 1 AND 5000 AND `row_count` BETWEEN 0 AND 5000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家CRM异步脱敏导出任务';

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND engine = 'InnoDB'
     AND table_name IN ('merchant_customer_segment','merchant_crm_export_task')) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND
        ((table_name = 'merchant_customer_segment' AND column_name IN
          ('merchant_id','filter_json','request_id','del_flag','version'))
         OR (table_name = 'merchant_crm_export_task' AND column_name IN
          ('merchant_id','merchant_owner_member_id','requester_member_id','status','version','storage_key','token_hash','request_id')))) = 13
  AND (SELECT COUNT(*) FROM (
        SELECT table_name, index_name, MIN(non_unique) AS non_unique,
               GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name IN ('merchant_customer_segment','merchant_crm_export_task')
        GROUP BY table_name, index_name
      ) exact_unique
      WHERE non_unique = 0 AND
        ((table_name = 'merchant_customer_segment' AND index_name = 'uk_merchant_customer_segment_request'
          AND cols = 'merchant_id,request_id')
         OR (table_name = 'merchant_customer_segment' AND index_name = 'uk_merchant_customer_segment_name'
          AND cols = 'merchant_id,name,del_flag')
         OR (table_name = 'merchant_crm_export_task' AND index_name = 'uk_merchant_crm_export_request'
          AND cols = 'merchant_id,request_id'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
         AND constraint_name IN ('chk_merchant_customer_segment_del','chk_merchant_crm_export_paid',
           'chk_merchant_crm_export_status','chk_merchant_crm_export_limit')) = 4
  AS assertion_ok;
