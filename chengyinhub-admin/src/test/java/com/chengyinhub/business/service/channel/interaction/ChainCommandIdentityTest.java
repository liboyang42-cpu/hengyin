package com.chengyinhub.business.service.channel.interaction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.service.channel.interaction.ChainParticipationTransition.Action.*;

class ChainCommandIdentityTest {
    private ChainCommandIdentity original() { return new ChainCommandIdentity(10, 20, 30, "original", JOIN, "one"); }
    @Test void exactRetryUsesOriginalReceipt() { assertTrue(original().matchesOriginal(original())); }
    @Test void absentReceiptIsNotAcknowledged() { assertFalse(original().matchesOriginal(null)); }
    @Test void anotherMemberCannotReuseChainRequestId() {
        assertThrows(IllegalStateException.class,
                () -> new ChainCommandIdentity(10, 20, 31, "original", JOIN, "one").matchesOriginal(original()));
    }
    @Test void changedConversationCannotReuseKey() {
        assertThrows(IllegalStateException.class,
                () -> new ChainCommandIdentity(10, 21, 30, "original", JOIN, "one").matchesOriginal(original()));
    }
    @Test void changedActionCannotTurnOriginalJoinIntoWithdrawal() {
        assertThrows(IllegalStateException.class,
                () -> new ChainCommandIdentity(10, 20, 30, "original", WITHDRAW, "one").matchesOriginal(original()));
    }
    @Test void changedContentCannotOverwriteOriginal() {
        assertThrows(IllegalStateException.class,
                () -> new ChainCommandIdentity(10, 20, 30, "original", JOIN, "two").matchesOriginal(original()));
    }
    @Test void wrongLookupIsRejectedRatherThanReportedAsReplay() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChainCommandIdentity(11, 20, 30, "original", JOIN, "one").matchesOriginal(original()));
        assertThrows(IllegalArgumentException.class,
                () -> new ChainCommandIdentity(10, 20, 30, "different", JOIN, "one").matchesOriginal(original()));
    }
    @Test void missingIdentityAndZeroScopeFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new ChainCommandIdentity(10, 20, 30, " ", JOIN, "one"));
        assertThrows(IllegalArgumentException.class, () -> new ChainCommandIdentity(0, 20, 30, "original", JOIN, "one"));
    }
}
