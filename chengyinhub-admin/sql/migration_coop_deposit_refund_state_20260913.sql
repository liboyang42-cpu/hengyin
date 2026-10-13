-- 本地准备，未执行生产。无历史退款/状态回填；旧4由查询独立补证，不自动派发。
-- 先核对这两项是否有结果；有冲突必须人工定位，不允许自动合账：
SELECT deposit_out_trade_no,COUNT(*) FROM coop_invite WHERE deposit_out_trade_no IS NOT NULL GROUP BY deposit_out_trade_no HAVING COUNT(*)>1;
SELECT deposit_refund_no,COUNT(*) FROM coop_invite WHERE deposit_refund_no IS NOT NULL GROUP BY deposit_refund_no HAVING COUNT(*)>1;
ALTER TABLE coop_invite
 ADD COLUMN deposit_refund_state VARCHAR(24) NULL COMMENT 'PENDING/DISPATCHING/PROCESSING/UNKNOWN/SUCCESS/MANUAL/LEGACY_UNCONFIRMED',
 ADD COLUMN deposit_refund_amount DECIMAL(10,2) NULL COMMENT '派发前固定退款金额',
 ADD COLUMN deposit_refund_lease_token VARCHAR(36) NULL,
 ADD COLUMN deposit_refund_lease_at DATETIME NULL,
 ADD COLUMN deposit_refund_requested_at DATETIME NULL,
 ADD COLUMN deposit_refund_confirmed_at DATETIME NULL,
 ADD COLUMN deposit_paid_transaction_id VARCHAR(64) NULL,
 ADD UNIQUE KEY uk_coop_deposit_trade (deposit_out_trade_no),
 ADD UNIQUE KEY uk_coop_deposit_refund (deposit_refund_no),
 ADD KEY idx_coop_deposit_refund_reconcile (deposit_refund_state,deposit_refund_lease_at);
