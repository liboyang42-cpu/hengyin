-- C2 / F0535 平台支付事实核对回执。
-- 后台订单详情「向渠道查询」每次落一行只追加的回执:渠道交易号、渠道状态、渠道金额、
-- 查询时本地保存的状态/交易号/金额,以及据此得出的核对结论(UNCONFIRMED/CONFIRMED/CONFLICT)。
-- 订单详情只读最新一行回执来展示「未确认/已确认/冲突 + 最后查询时间」,本地保存的订单状态
-- 永远不被当成已独立核验的支付事实。只读查询,不改订单、不改资金。
-- 可重复执行(CREATE TABLE IF NOT EXISTS)。
-- ⚠️ 只在库上跑:合入前须走 chengyin-db-migrate 只读预检 + 用户确认后落库;本文件不自动执行。

CREATE TABLE IF NOT EXISTS oms_order_payment_check (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL COMMENT 'oms_order.id',
  channel VARCHAR(16) NOT NULL COMMENT '渠道:WECHAT',
  out_trade_no VARCHAR(64) NOT NULL COMMENT '向渠道查询用的商户单号(=order_sn)',
  query_status VARCHAR(16) NOT NULL COMMENT 'OK / NOT_EXIST / FAILED',
  channel_trade_state VARCHAR(32) NULL DEFAULT NULL,
  channel_transaction_id VARCHAR(64) NULL DEFAULT NULL,
  channel_total_fen INT NULL DEFAULT NULL,
  saved_status INT NULL DEFAULT NULL COMMENT '查询时本地保存的订单状态',
  saved_transaction_id VARCHAR(64) NULL DEFAULT NULL,
  saved_pay_amount_fen INT NULL DEFAULT NULL,
  verdict VARCHAR(16) NOT NULL COMMENT 'UNCONFIRMED / CONFIRMED / CONFLICT',
  detail VARCHAR(255) NULL DEFAULT NULL COMMENT '结论说明',
  error_message VARCHAR(255) NULL DEFAULT NULL COMMENT '查询失败原因',
  operator_id BIGINT NULL DEFAULT NULL COMMENT '后台 sys_user.user_id',
  query_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_order_payment_check_order (order_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单支付事实渠道查询回执(只追加)';

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'oms_order_payment_check') = 16 AS assertion_ok;
