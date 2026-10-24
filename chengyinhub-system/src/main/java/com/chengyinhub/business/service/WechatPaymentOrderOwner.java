package com.chengyinhub.business.service;

/** Existing domain order facts only. Caller owns the transaction; implementations lock, never settle. */
public interface WechatPaymentOrderOwner {
    record Snapshot(String type, Number id, java.math.BigDecimal amount, boolean cents, boolean payable) { }
    Snapshot lock(String businessOutTradeNo);
}
