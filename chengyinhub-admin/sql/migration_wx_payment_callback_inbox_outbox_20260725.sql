-- P1-5 付费台账合同:支付/退款/转账回调 inbox + 出站重试 outbox。
-- 复用 B(内容回调)已验证过的 fencing 模式(migration_wx_content_callback_inbox_20260724.sql),
-- owner_token + fence_token 两层,只改名给支付回调用,只建表不接业务逻辑
-- (接入 WechatPayServiceImpl/RefundServiceImpl 的具体消费代码留给 P1-6 的 K)。
-- 仅随代码交付，必须按 chengyin-db-migrate 流程人工预检与授权后执行；本任务禁止执行生产 DDL。
-- 2026-07-25 已 --describe 生产回读确认两表均不存在，无命名冲突。

-- ============================================================================
-- inbox:微信支付/退款/转账回调去重与派发(照抄 wx_content_callback_inbox 的字段设计)
-- ============================================================================
CREATE TABLE IF NOT EXISTS `wx_payment_callback_inbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `notify_hash` BINARY(32) NOT NULL COMMENT '微信回调唯一标识(如通知ID/密文)SHA-256；用于去重同一回调的重复投递,不保存原值',
  `biz_type` SMALLINT UNSIGNED NOT NULL COMMENT '1支付回调 2退款回调 3转账回调',
  `biz_id` BIGINT NOT NULL COMMENT '内部业务单据ID(cms_registration.id / refund_application.id / member_cash_withdraw.id)',
  `event_type` VARCHAR(32) DEFAULT NULL COMMENT '微信事件类型,如 TRANSACTION.SUCCESS/REFUND.SUCCESS',
  `state` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 waiting,1 processing,2 done,3 retryable,4 ignored_stale',
  `owner_token` BINARY(16) DEFAULT NULL COMMENT '当前持有处理权的 worker 令牌',
  `fence_token` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '单调递增隔离令牌,防止旧 worker 在重新抢占后继续写',
  `lease_until` DATETIME(3) DEFAULT NULL COMMENT '处理租约到期时间,过期可被其他 worker 抢占',
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `last_error_code` VARCHAR(64) DEFAULT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wx_payment_callback_notify_hash` (`notify_hash`),
  KEY `idx_wx_payment_callback_claim` (`state`, `lease_until`),
  KEY `idx_wx_payment_callback_target` (`biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信支付/退款/转账回调 inbox/fencing(P1-5)';

-- ============================================================================
-- outbox:我方主动发起的出站重试队列(定时查询终态/补发确定性重试),同一套 fencing
-- ============================================================================
-- 任务书 1.6 节铁律:
--   · 重试次数上限建议3次(间隔递增),超过进人工队列,不设计成无限重试 → max_attempts 默认3。
--   · 涉及金额变化的重试(如二次转账)一律不自动重试，只能人工触发 → money_affecting=1 时
--     达到 state=manual_required 后必须停手动介入，禁止继续派发。
CREATE TABLE IF NOT EXISTS `wx_payment_callback_outbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `biz_type` SMALLINT UNSIGNED NOT NULL COMMENT '1支付终态查询 2退款终态查询 3转账终态查询(仅查询类;涉及金额变化的动作见 money_affecting)',
  `biz_id` BIGINT NOT NULL COMMENT '内部业务单据ID',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '出站请求幂等键(如 out_trade_no/out_refund_no/out_batch_no)',
  `money_affecting` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '1=涉及金额变化的动作(如二次转账),不允许自动重试,只能人工触发',
  `state` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 pending,1 dispatching,2 done,3 retryable,4 manual_required',
  `owner_token` BINARY(16) DEFAULT NULL,
  `fence_token` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `lease_until` DATETIME(3) DEFAULT NULL,
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `max_attempts` INT UNSIGNED NOT NULL DEFAULT 3 COMMENT '任务书1.6:自动重试上限,默认3次,超过转 manual_required',
  `next_attempt_at` DATETIME(3) DEFAULT NULL COMMENT '下次自动重试时间(间隔递增由消费方计算写入)',
  `last_error_code` VARCHAR(64) DEFAULT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wx_payment_outbox_idempotency` (`idempotency_key`),
  KEY `idx_wx_payment_outbox_claim` (`state`, `next_attempt_at`),
  KEY `idx_wx_payment_outbox_target` (`biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信支付/退款/转账出站重试 outbox/fencing(P1-5)';
