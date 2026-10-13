package com.chengyinhub.business.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MerchantFinanceStateResolverTest {

    @Test
    void mapsTwoFactAxesToOneMutuallyExclusiveDisplayState() {
        assertEquals("PENDING_SETTLEMENT", MerchantFinanceStateResolver.resolve("ACTIVE", "PENDING"));
        assertEquals("SETTLED", MerchantFinanceStateResolver.resolve("ACTIVE", "SETTLED"));
        assertEquals("NO_CASH_SETTLEMENT", MerchantFinanceStateResolver.resolve("ACTIVE", "NOT_APPLICABLE"));
        assertEquals("REVERSED_BEFORE_SETTLEMENT", MerchantFinanceStateResolver.resolve("REVERSED", "PENDING"));
        assertEquals("ADJUSTMENT_PENDING", MerchantFinanceStateResolver.resolve("REVERSED", "ADJUSTMENT_PENDING"));
        assertEquals("ADJUSTED", MerchantFinanceStateResolver.resolve("REVERSED", "ADJUSTED"));
        assertEquals("LEDGER_ERROR", MerchantFinanceStateResolver.resolve("ACTIVE", "ERROR"));
    }
}
