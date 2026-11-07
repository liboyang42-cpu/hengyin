package com.chengyinhub.business.support;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class FundsHoldPolicyTest {

    @Test
    void payableUsesEndPlusExactlyFourteenDaysWithoutMutatingTheSource() {
        Date end = new Date(1_000_000L);
        Date now = new Date(2_000_000L);

        Date payable = FundsHoldPolicy.payableAfterEnd(end, now);

        assertEquals(14, FundsHoldPolicy.WITHDRAWABLE_AFTER_END_DAYS);
        assertEquals(1_209_600_000L, FundsHoldPolicy.WITHDRAWABLE_AFTER_END_MS);
        assertEquals(end.getTime() + 1_209_600_000L, payable.getTime());
        assertNotSame(end, payable);
        assertEquals(1_000_000L, end.getTime());
        assertEquals(2_000_000L, now.getTime());
    }

    @Test
    void missingEndKeepsOriginalNowFallbackAndFourteenDayDuration() {
        Date now = new Date(2_000_000L);

        Date payable = FundsHoldPolicy.payableAfterEnd(null, now);

        assertEquals(now.getTime() + 1_209_600_000L, payable.getTime());
        assertNotSame(now, payable);
        assertEquals(2_000_000L, now.getTime());
    }
}
