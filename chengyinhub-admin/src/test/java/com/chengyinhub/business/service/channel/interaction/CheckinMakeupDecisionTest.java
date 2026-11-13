package com.chengyinhub.business.service.channel.interaction;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.service.channel.interaction.CheckinMakeupDecision.*;

class CheckinMakeupDecisionTest {
    private final LocalDate today = LocalDate.of(2026, 11, 5);
    private final Policy policy = new Policy(7, true, today.minusDays(3), today.minusDays(1));
    @Test void noPublishedPolicyCannotAllowMakeup() {
        assertEquals(Result.NOT_CONFIGURED, decide(today.minusDays(1), today, null, false));
        assertEquals(Result.NOT_CONFIGURED, decide(today.minusDays(1), today,
                new Policy(7, false, today.minusDays(3), today.minusDays(1)), false));
    }
    @Test void existingRegularOrMakeupRecordOnlyReturnsOriginalReceipt() {
        assertEquals(Result.ORIGINAL_RECEIPT_ONLY, decide(today.minusDays(1), today, policy, true));
    }
    @Test void policyRemovalCannotTurnRecoveryIntoNewAward() {
        assertEquals(Result.ORIGINAL_RECEIPT_ONLY, decide(today.minusDays(20), today, null, true));
    }
    @Test void bothPublishedWindowBoundariesAreIncluded() {
        assertEquals(Result.ELIGIBLE, decide(today.minusDays(3), today, policy, false));
        assertEquals(Result.ELIGIBLE, decide(today.minusDays(1), today, policy, false));
    }
    @Test void outsideWindowTodayAndFutureAreRejected() {
        assertEquals(Result.OUTSIDE_WINDOW, decide(today.minusDays(4), today, policy, false));
        assertEquals(Result.OUTSIDE_WINDOW, decide(today, today, policy, false));
        assertEquals(Result.OUTSIDE_WINDOW, decide(today.plusDays(1), today, policy, false));
    }
    @Test void malformedWindowAndVersionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Policy(0, true, today, today));
        assertThrows(IllegalArgumentException.class, () -> new Policy(7, true, today, today.minusDays(1)));
    }
}
