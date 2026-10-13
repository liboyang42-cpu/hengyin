-- 微信内容安全回调 inbox / fencing 权威表。
-- 仅随代码交付，必须按 chengyin-db-migrate 流程人工预检与授权后执行；本任务禁止执行生产 DDL。
CREATE TABLE IF NOT EXISTS `wx_content_callback_inbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `trace_hash` BINARY(32) NOT NULL COMMENT 'trace_id SHA-256；不保存原值',
  `biz_type` SMALLINT UNSIGNED NOT NULL,
  `biz_id` BIGINT NOT NULL,
  `media_hash` BINARY(32) DEFAULT NULL COMMENT '送检媒体 URL SHA-256；兼容旧任务时可空',
  `verdict` TINYINT UNSIGNED DEFAULT NULL COMMENT '0 pass, 1 risky',
  `state` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 waiting,1 processing,2 done,3 retryable,4 ignored_stale,5 manual_review(permanent)',
  `owner_token` BINARY(16) DEFAULT NULL,
  `fence_token` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `lease_until` DATETIME(3) DEFAULT NULL,
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `last_error_code` VARCHAR(64) DEFAULT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wx_content_callback_trace_hash` (`trace_hash`),
  KEY `idx_wx_content_callback_claim` (`state`, `lease_until`),
  KEY `idx_wx_content_callback_target` (`biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信媒体内容审核回调 inbox/fencing';
