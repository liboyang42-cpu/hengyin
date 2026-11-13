package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 重新支付必须复用稳定业务单号，并由 Service 执行当前可售/可收款 Gate。 */
class RegistrationPaymentRetryContractTest {

    @Test
    void applicationBoundaryOwnsRetryAndReturnsMappedResult() throws Exception {
        java.lang.reflect.Method retry = IRegistrationCheckoutService.class.getMethod(
                "retryPayment", Long.class, Long.class);
        assertEquals(RegistrationCheckoutResult.class, retry.getReturnType());
        assertNotNull(ICmsRegistrationService.class.getMethod(
                "beginPaymentAttempt", Long.class, Long.class));
        assertEquals(java.util.List.class, ICmsRegistrationService.class.getMethod(
                "completeZeroAmountPaymentForRetry", Long.class, Long.class).getReturnType());
    }

    /** 自玩票虚拟支付(XPAY)新增的边界：带 jsCode 的重新支付、开通、渠道退款、签发前准入、退款终态收口。 */
    @Test
    void selfPlayXpayBoundariesExist() throws Exception {
        assertEquals(RegistrationCheckoutResult.class, IRegistrationCheckoutService.class.getMethod(
                "retryPayment", Long.class, Long.class, boolean.class, String.class).getReturnType());
        assertEquals(String.class, ICmsRegistrationService.class.getMethod(
                "completeXpayPayment", String.class, String.class, long.class).getReturnType());
        assertEquals(boolean.class, ICmsRegistrationService.class.getMethod(
                "applyXpayChannelRefund", String.class).getReturnType());
        assertNotNull(ICmsRegistrationService.class.getMethod("requirePayableForXpay", Long.class, Long.class));
        assertEquals(int.class, IRefundService.class.getMethod("settleChannelRefundSuccess", Long.class).getReturnType());
    }
}
