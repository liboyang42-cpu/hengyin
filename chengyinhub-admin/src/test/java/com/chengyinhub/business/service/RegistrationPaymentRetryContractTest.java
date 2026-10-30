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
}
