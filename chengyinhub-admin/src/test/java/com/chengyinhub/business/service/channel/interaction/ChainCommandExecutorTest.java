package com.chengyinhub.business.service.channel.interaction;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.service.channel.interaction.ChainParticipationTransition.*;
import static com.chengyinhub.business.service.channel.interaction.ChainCommandExecutor.*;

class ChainCommandExecutorTest {
    private static final Instant DEADLINE = Instant.parse("2026-11-06T00:00:00Z");
    private final Probe store = new Probe();
    private final ChainCommandIdentity command = command("request", Action.JOIN, "one");
    private ChainCommandIdentity command(String key, Action action, String content) {
        return new ChainCommandIdentity(10, 20, 30, key, action, content);
    }
    private Snapshot snapshot(EntryState state, int count) {
        return new Snapshot(10, 20, 30, 2, count, true, DEADLINE, 8, 4, state);
    }
    private Receipt execute() {
        return new ChainCommandExecutor(store, Clock.fixed(DEADLINE.minusSeconds(1), ZoneOffset.UTC)).execute(command);
    }
    private Receipt original() {
        return new Receipt("durable-original", command,
                calculate(snapshot(EntryState.ABSENT, 0), Action.JOIN, DEADLINE.minusSeconds(10)));
    }

    @Test void newCommandReturnsOnlyAfterTransactionCommit() {
        Receipt result = execute();
        assertEquals(List.of("begin", "authorize", "receipt", "lock", "persist", "commit"), store.calls);
        assertSame(store.written, result);
        assertEquals(1, result.proposal().nextActiveCount());
    }
    @Test void originalJoinReplayNeverReactivatesWithdrawnRelation() {
        store.original = original();
        store.current = snapshot(EntryState.WITHDRAWN, 0);
        assertSame(store.original, execute());
        assertEquals(List.of("begin", "authorize", "receipt", "commit"), store.calls);
        assertNull(store.written);
    }
    @Test void replayStillRequiresCurrentAuthorizationBeforeReceiptRead() {
        store.original = original();
        store.deny = true;
        assertThrows(SecurityException.class, this::execute);
        assertEquals(List.of("begin", "authorize", "rollback"), store.calls);
    }
    @Test void sameKeyChangedContentFailsWithoutLockOrWrite() {
        store.original = new Receipt("other", command("request", Action.JOIN, "changed"), original().proposal());
        assertThrows(IllegalStateException.class, this::execute);
        assertEquals(List.of("begin", "authorize", "receipt", "rollback"), store.calls);
    }
    @Test void wrongReceiptLookupIsNotAcknowledged() {
        store.original = new Receipt("other", command("different", Action.JOIN, "one"), original().proposal());
        assertThrows(IllegalArgumentException.class, this::execute);
        assertFalse(store.calls.contains("persist"));
    }
    @Test void wrongSnapshotScopeFailsBeforeWrite() {
        store.current = new Snapshot(10, 21, 30, 2, 0, true, DEADLINE, 8, 4, EntryState.ABSENT);
        assertThrows(IllegalStateException.class, this::execute);
        assertFalse(store.calls.contains("persist"));
    }
    @Test void noChangeStillPersistsOriginalCommandReceipt() {
        store.current = snapshot(EntryState.ACTIVE, 1);
        assertEquals(Effect.NO_CHANGE, execute().proposal().effect());
        assertTrue(store.calls.contains("persist"));
    }
    @Test void fullChainFailsBeforePersistence() {
        store.current = snapshot(EntryState.ABSENT, 2);
        assertThrows(IllegalStateException.class, this::execute);
        assertFalse(store.calls.contains("persist"));
    }
    @Test void lockWaitCannotUseStaleRequestArrivalTime() {
        Clock afterLock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() {
                assertTrue(store.calls.contains("lock"));
                return DEADLINE;
            }
        };
        assertThrows(IllegalStateException.class, () -> new ChainCommandExecutor(store, afterLock).execute(command));
        assertFalse(store.calls.contains("persist"));
    }
    @Test void persistFailurePropagatesWithoutAutomaticRetry() {
        store.failPersist = true;
        assertThrows(IllegalStateException.class, this::execute);
        assertEquals(1, store.calls.stream().filter("begin"::equals).count());
        assertEquals("rollback", store.calls.get(store.calls.size() - 1));
    }
    @Test void unknownCommitOutcomeDoesNotReturnOptimisticSuccessOrRetry() {
        store.failCommit = true;
        assertThrows(IllegalStateException.class, this::execute);
        assertEquals(1, store.calls.stream().filter("persist"::equals).count());
    }
    @Test void nullStoredReceiptFailsInsideTransaction() {
        store.nullReceipt = true;
        assertThrows(NullPointerException.class, this::execute);
        assertEquals("rollback", store.calls.get(store.calls.size() - 1));
    }
    @Test void mismatchedStoredTransitionFailsInsideTransaction() {
        store.wrongProposal = true;
        assertThrows(IllegalStateException.class, this::execute);
        assertEquals("rollback", store.calls.get(store.calls.size() - 1));
    }
    @Test void receiptCannotBindAnotherMember() {
        Proposal other = new Proposal(10, 20, 31, 8, 4, 0, 1, Effect.INSERT_RELATION);
        assertThrows(IllegalArgumentException.class, () -> new Receipt("id", command, other));
        assertThrows(IllegalArgumentException.class, () -> new Receipt(" ", command, original().proposal()));
    }

    /** Ordering probe only: not a durable adapter or evidence of database rollback/concurrency. */
    private final class Probe implements Store, Transaction {
        final List<String> calls = new ArrayList<>();
        Snapshot current = snapshot(EntryState.ABSENT, 0);
        Receipt original;
        Receipt written;
        boolean deny, failPersist, failCommit, nullReceipt, wrongProposal;
        public Receipt inTransaction(Work work) {
            calls.add("begin");
            Receipt result;
            try { result = work.run(this); }
            catch (RuntimeException error) { calls.add("rollback"); throw error; }
            calls.add("commit");
            if (failCommit) throw new IllegalStateException("Unknown commit outcome");
            return result;
        }
        public void requireCurrentAccess(ChainCommandIdentity identity) {
            calls.add("authorize");
            if (deny) throw new SecurityException("Unavailable");
        }
        public Receipt findOriginal(long chainId, String requestId) {
            calls.add("receipt");
            assertEquals(10, chainId); assertEquals("request", requestId);
            return original;
        }
        public Snapshot lockCurrent(ChainCommandIdentity identity) {
            calls.add("lock"); return current;
        }
        public Receipt persist(ChainCommandIdentity identity, Proposal proposal) {
            calls.add("persist");
            if (failPersist) throw new IllegalStateException("CAS or unique conflict");
            if (nullReceipt) return null;
            if (wrongProposal) proposal = new Proposal(10, 20, 30, 99, 4, 0, 1, Effect.INSERT_RELATION);
            written = new Receipt("durable-new", identity, proposal);
            return written;
        }
    }
}
