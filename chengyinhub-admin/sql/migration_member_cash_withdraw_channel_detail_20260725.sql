-- P1-5 付费台账合同:member_cash_withdraw 补齐商业化清单 §6.2 字段清单里真缺失的两列。
-- 依据:2026-07-25 --describe 生产回读核实,§6.2 要求的14项字段里12项已存在
-- (withdrawal_id=id / 平台幂等键=out_batch_no / 微信批次号=transfer_batch_id / 渠道状态=status+wechat_batch_status /
--  提交时间=submit_time / 最后查询时间=last_query_time / 完成时间=finish_time / 失败原因=fail_reason /
--  余额扣减流水ID=balance_deduct_id / 余额回补流水ID=balance_refund_id / 人工处理人=manual_operator /
--  人工处理说明=manual_note / 审计trace ID=trace_id),真缺失只有下面两列:
--   1) 微信明细号(渠道侧生成的 detail_id,区别于我们自己生成的 out_detail_no)——
--      微信转账明细查询接口 TransferDetailEntity.getDetailId() 已可取得,但当前
--      WechatPayServiceImpl.queryTransferResult()/CashWithdrawServiceImpl 均未持久化,查询结果被丢弃。
--   2) 渠道错误码(TransferDetailEntity.getFailReason() 是结构化 FailReasonType 枚举,而非
--      fail_reason 里那段人类可读文案)——现状把 ServiceException.getErrorCode() 拼进 fail_reason
--      文本里(如"微信拒绝(XXX)"),没有独立的机器可读错误码列,对账/告警脚本无法按码分类处理。
-- ⚠️ 本迁移只加列,不改动 CashWithdrawServiceImpl/WechatPayServiceImpl 的核心逻辑——
--    捕获/写入这两列的接线工作留给 P1-6 的 L(feature/p1-wechat-payout)。
-- 幂等:按 information_schema 判断后再加列,可重复执行。

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw' AND column_name = 'wechat_detail_id');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE member_cash_withdraw ADD COLUMN wechat_detail_id VARCHAR(64) NULL COMMENT ''微信明细号(渠道侧detail_id,对账用)'' AFTER transfer_batch_id',
  'SELECT ''wechat_detail_id already exists, skip''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw' AND column_name = 'channel_error_code');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE member_cash_withdraw ADD COLUMN channel_error_code VARCHAR(64) NULL COMMENT ''渠道错误码(微信FailReasonType枚举/ServiceException.getErrorCode(),机器可读,区别于fail_reason人类文案)'' AFTER fail_reason',
  'SELECT ''channel_error_code already exists, skip''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
