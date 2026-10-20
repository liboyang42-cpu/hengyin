package com.chengyinhub.business.service.impl;

import java.time.Instant;

/** Server-only typed handoff from the existing official signature-verified WeChat callback.
 * No HTTP JSON constructor, public factory, client paid flag, subscription or order status is proof.
 * Package-private construction is a misuse guard, not cryptographic evidence on its own. */
public final class VerifiedWorkshopPayment {
    private final String orderNumber, transactionId, appId, merchantId, currency;
    private final long amountMinor;
    private final Instant paidAt;
    VerifiedWorkshopPayment(String orderNumber, String transactionId, String appId, String merchantId,
                           String currency, long amountMinor, Instant paidAt) {
        if (orderNumber == null || !orderNumber.matches("W18_[A-Za-z0-9_-]{16,64}")
                || !identifier(transactionId) || !identifier(appId) || !identifier(merchantId)
                || !"CNY".equals(currency) || amountMinor <= 0 || amountMinor > Integer.MAX_VALUE || paidAt == null)
            throw new IllegalArgumentException("invalid verified Workshop payment");
        this.orderNumber=orderNumber;this.transactionId=transactionId;this.appId=appId;this.merchantId=merchantId;
        this.currency=currency;this.amountMinor=amountMinor;this.paidAt=paidAt;
    }
    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,64}"); }
    public String getOrderNumber(){return orderNumber;} public String getTransactionId(){return transactionId;}
    public String getAppId(){return appId;} public String getMerchantId(){return merchantId;}
    public String getCurrency(){return currency;} public long getAmountMinor(){return amountMinor;} public Instant getPaidAt(){return paidAt;}
    @Override public String toString(){return "VerifiedWorkshopPayment[redacted]";}
}
