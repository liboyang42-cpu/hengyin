package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.marketplace.paid.WorkshopPaidPaymentHandler;
import com.wechat.pay.java.service.payments.model.Transaction;
import java.time.OffsetDateTime;

/** Package-private handoff only after the official SDK signature parser in WechatPayServiceImpl.
 * Not an HTTP deserializer and not an alternate payment verifier. Missing capabilities fail closed. */
final class WorkshopPaidNotificationRoute {
    private WorkshopPaidNotificationRoute() { }
    static boolean dispatch(Transaction transaction, WorkshopPaidPaymentHandler handler) {
        if (handler==null || transaction==null || transaction.getTradeState()!=Transaction.TradeStateEnum.SUCCESS
                || transaction.getAmount()==null || transaction.getAmount().getTotal()==null || transaction.getSuccessTime()==null) return false;
        try {
            VerifiedWorkshopPayment verified=new VerifiedWorkshopPayment(transaction.getOutTradeNo(),transaction.getTransactionId(),
                    transaction.getAppid(),transaction.getMchid(),transaction.getAmount().getCurrency(),
                    transaction.getAmount().getTotal().longValue(),OffsetDateTime.parse(transaction.getSuccessTime()).toInstant());
            return handler.accept(verified);
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
