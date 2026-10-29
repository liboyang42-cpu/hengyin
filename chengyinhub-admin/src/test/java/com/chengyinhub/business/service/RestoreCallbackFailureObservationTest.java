package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.WxPaymentCallbackInbox;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Observation seam only; these doubles are not funds/MySQL/inbox-DONE evidence. */
class RestoreCallbackFailureObservationTest {
    @Test
    void mutationExceptionRollsBackAndRetainsTheExactCauseInsteadOfBecomingSuccess() {
        var manager = mock(PlatformTransactionManager.class); var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        var observed = new AtomicReference<RuntimeException>();
        var inbox = PaymentCallbackInboxStubs.passThrough(manager, null, observed::set);
        inbox.register("original", 1, 12L, "SUCCESS");
        var original = new IllegalStateException("PRIVATE_SECRET_CANARY");
        assertFalse(inbox.process("original", new IWxPaymentCallbackInboxService.Mutation() {
            public boolean isCurrent(WxPaymentCallbackInbox row) { return true; }
            public boolean apply(WxPaymentCallbackInbox row) { throw original; }
        }));
        assertSame(original, observed.get()); verify(manager).rollback(status); verify(manager, never()).commit(any());
    }

    @Test
    void observationDoesNotReplaceTheOriginalSuccessfulMutationOrInventDone() {
        var observed = new AtomicReference<RuntimeException>();
        var inbox = PaymentCallbackInboxStubs.passThrough(null, null, observed::set);
        inbox.register("original", 1, 12L, "SUCCESS");
        var applied = new java.util.concurrent.atomic.AtomicInteger();
        IWxPaymentCallbackInboxService.Mutation mutation = new IWxPaymentCallbackInboxService.Mutation() {
            public boolean isCurrent(WxPaymentCallbackInbox row) { return true; }
            public boolean apply(WxPaymentCallbackInbox row) { applied.incrementAndGet(); return true; }
        };
        assertTrue(inbox.process("original", mutation)); assertTrue(inbox.process("original", mutation));
        assertEquals(2, applied.get()); assertNull(observed.get());
    }
}
