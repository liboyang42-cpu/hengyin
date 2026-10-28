package com.chengyinhub.business.domain.dto;

/** A committed local consumption of one exact provider fact; not a provider query or an inbox lease. */
public record RegistrationPaymentFactReceipt(Long registrationId, String registrationNo,
        Long paymentFactId, String paymentFactHash, String transactionId) {
    public WechatVerifiedPaymentFact.Reference reference() {
        if (registrationId == null || registrationId <= 0 || paymentFactId == null)
            throw new IllegalStateException("原报名支付事实关联不完整");
        return new WechatVerifiedPaymentFact.Reference(paymentFactId, paymentFactHash);
    }
}
