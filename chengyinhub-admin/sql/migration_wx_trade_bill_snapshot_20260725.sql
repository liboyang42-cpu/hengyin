-- P1-6 N块(feature/p1-four-ledger-reconcile):微信交易流水本地留痕表。
--
-- 【架构决策,已定稿不再评估】微信交易流水的落地方式 = T+1 账单下载入库,不是实时拉取查单接口。
--   微信官方为对账场景设计了「交易账单」(download_bill, bill_type=ALL/SUCCESS/REFUND)和
--   「资金账单」(download_fund_flow_bill,account_type=BASIC/OPERATION/FEES)两个官方接口,
--   T+1 早10点后可下载 T 日完整账单(含当天所有支付成功/退款成功记录)——这是微信官方给的
--   「当天应该有哪些交易」的权威清单。实时查单接口是"一笔一笔问状态",做不了批量交叉对账
--   (不知道要问哪些单号),故不采用。
--
-- 【本轮范围】只建本地快照表 + DTO + 空的下载/解析 service 接口签名 + T+1 定时任务框架(TODO骨架)。
--   ⚠️ 不接入真实微信账单下载 API,不跑通,不伪造假数据——真实商户联调环境是 P1-7 范围。
--   本表本轮上线后预期长期是 0 行,直到 P1-7 真正接入下载逻辑,这是符合预期的状态,不是 bug。
--
-- 仅随代码交付，必须按 chengyin-db-migrate 流程人工预检与授权后执行；本任务禁止执行生产 DDL。
-- 2026-07-25 已 --describe 生产回读确认该表不存在，无命名冲突。

-- ======================= 第一步:只读预检(执行前必做) =======================
-- SHOW TABLES LIKE 'wx_trade_bill_snapshot';  -- 期望:空结果(2026-07-25 已确认)

-- ======================= 第二步:建表(幂等,可重复执行) =======================
CREATE TABLE IF NOT EXISTS `wx_trade_bill_snapshot` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `bill_date` DATE NOT NULL COMMENT '账单归属日期(T日,微信账单文件的自然日,非落库时间)',
  `wx_trade_no` VARCHAR(64) NOT NULL COMMENT '微信侧单号:支付行=微信支付订单号(transaction_id);退款行=微信退款单号(refund_id)',
  `out_trade_no` VARCHAR(64) NOT NULL COMMENT '商户侧业务单号,对应 cms_registration.registration_no(下单时作为 out_trade_no 传给微信)',
  `out_refund_no` VARCHAR(64) DEFAULT NULL COMMENT '商户侧退款单号(仅退款行有值,对应 RefundApplication.refundNo)',
  `biz_type` TINYINT UNSIGNED NOT NULL COMMENT '1=支付成功 2=退款成功(与账单文件的"交易状态/退款状态"列对应,只落成功态,失败/关闭态不落)',
  `amount` DECIMAL(12,2) NOT NULL COMMENT '金额(元)。★绝对值不带正负,方向由 biz_type 承载,别塞负数退款',
  `trade_time` DATETIME NOT NULL COMMENT '微信侧交易时间(账单文件"交易时间"列,不是落库时间)',
  `raw_ingested_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本行从账单文件解析落库的时间,供追查"账单何时导入"',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wx_trade_bill_no` (`wx_trade_no`, `biz_type`) COMMENT '同一笔支付/退款重复下载同一天账单或跨天重复出现时防重复入账',
  KEY `idx_wx_trade_bill_date` (`bill_date`),
  KEY `idx_wx_trade_bill_out_trade_no` (`out_trade_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信交易账单(T+1下载)本地留痕快照,供四账对账消费(P1-6 N块)';

-- ======================= 第三步:落库后回读 =======================
-- SHOW CREATE TABLE wx_trade_bill_snapshot;

-- ======================= 可逆 =======================
-- 本轮上线后预期长期 0 行(P1-7 前无真实下载逻辑写入),可安全 DROP:
--   DROP TABLE IF EXISTS wx_trade_bill_snapshot;
-- ⚠️ P1-7 真实接入下载逻辑并产生数据后,这张表变成对账留痕,别再 DROP。
