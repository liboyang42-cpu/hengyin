package com.chengyinhub.business.service.channel.interaction;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.service.channel.interaction.ChainParticipationTransition.*;

class ChainParticipationTransitionTest {
    private final Instant deadline = Instant.parse("2026-11-06T00:00:00Z");
    private Snapshot row(int count, EntryState state) {
        return new Snapshot(10, 20, 30, 2, count, true, deadline, 8, 4, state);
    }
    @Test void firstJoinCarriesAllAtomicPreconditions() {
        Proposal p = calculate(row(1, EntryState.ABSENT), Action.JOIN, deadline.minusSeconds(1));
        assertEquals(Effect.INSERT_RELATION, p.effect());
        assertEquals(10, p.chainId()); assertEquals(20, p.conversationId()); assertEquals(30, p.memberId());
        assertEquals(8, p.expectedChainVersion()); assertEquals(4, p.expectedEntryVersion());
        assertEquals(1, p.expectedActiveCount()); assertEquals(2, p.nextActiveCount());
    }
    @Test void fullChainCannotAllocate() {
        assertThrows(IllegalStateException.class,
                () -> calculate(row(2, EntryState.ABSENT), Action.JOIN, deadline.minusSeconds(1)));
    }
    @Test void exactDeadlineIsClosed() {
        assertThrows(IllegalStateException.class, () -> calculate(row(1, EntryState.ABSENT), Action.JOIN, deadline));
    }
    @Test void closedChainCannotAllocateBeforeDeadline() {
        Snapshot closed = new Snapshot(10, 20, 30, 2, 1, false, deadline, 8, 4, EntryState.ABSENT);
        assertThrows(IllegalStateException.class, () -> calculate(closed, Action.JOIN, deadline.minusSeconds(1)));
    }
    @Test void existingActiveRelationDoesNotAllocateAgain() {
        Proposal p = calculate(row(2, EntryState.ACTIVE), Action.JOIN, deadline);
        assertEquals(Effect.NO_CHANGE, p.effect()); assertEquals(2, p.nextActiveCount());
    }
    @Test void withdrawalReleasesExactlyOneSlot() {
        Proposal p = calculate(row(2, EntryState.ACTIVE), Action.WITHDRAW, deadline);
        assertEquals(Effect.WITHDRAW_RELATION, p.effect()); assertEquals(1, p.nextActiveCount());
    }
    @Test void repeatedWithdrawalDoesNotReleaseAnotherMembersSlot() {
        Proposal p = calculate(row(1, EntryState.WITHDRAWN), Action.WITHDRAW, deadline);
        assertEquals(Effect.NO_CHANGE, p.effect()); assertEquals(1, p.nextActiveCount());
    }
    @Test void absentWithdrawalCannotReleaseSlot() {
        assertEquals(1, calculate(row(1, EntryState.ABSENT), Action.WITHDRAW, deadline).nextActiveCount());
    }
    @Test void rejoinReusesRelationRatherThanInsertingDuplicate() {
        Proposal p = calculate(row(1, EntryState.WITHDRAWN), Action.JOIN, deadline.minusSeconds(1));
        assertEquals(Effect.REACTIVATE_RELATION, p.effect()); assertEquals(2, p.nextActiveCount());
    }
    @Test void contradictoryStoredCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> row(0, EntryState.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> row(3, EntryState.ABSENT));
        assertThrows(IllegalArgumentException.class, () -> row(-1, EntryState.ABSENT));
    }
}
