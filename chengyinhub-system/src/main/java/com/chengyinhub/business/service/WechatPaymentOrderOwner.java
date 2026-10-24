package com.chengyinhub.business.service;

/** Existing domain order facts only. Caller owns the transaction; implementations lock, never settle. */
public interface WechatPaymentOrderOwner {
    record Snapshot(String type, Number id, java.math.BigDecimal amount, boolean cents, boolean payable) { }
    final class MissingOrderException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public MissingOrderException() { super("原业务支付单不存在，须保留渠道事实人工核验"); }
    }
    Snapshot lock(String businessOutTradeNo);
}
