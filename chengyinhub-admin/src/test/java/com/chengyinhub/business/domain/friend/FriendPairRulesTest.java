package com.chengyinhub.business.domain.friend;

import org.junit.jupiter.api.Test;

import static com.chengyinhub.business.domain.friend.FriendPairRules.*;
import static org.junit.jupiter.api.Assertions.*;

class FriendPairRulesTest
{
    private static final long A = 7;
    private static final long B = 19;

    private Pair apply(Pair pair, long actor, Command command)
    {
        return FriendPairRules.apply(pair, actor, pair.version(), command);
    }

    private Pair pending() { return apply(empty(A, B), A, Command.REQUEST); }
    private Pair friends() { return apply(pending(), B, Command.ACCEPT); }

    @Test void pairIdsAreCanonical()
    {
        assertEquals(empty(A, B), empty(B, A));
        assertThrows(IllegalArgumentException.class, () -> empty(A, A));
        assertThrows(IllegalArgumentException.class, () -> empty(0, B));
        assertThrows(IllegalArgumentException.class, () -> empty(-1, B));
    }

    @Test void firstRequestIsNotFriendship()
    {
        Pair pair = pending();
        assertFalse(pair.friends());
        assertEquals(1, pair.version());
        assertEquals(Relation.OUTGOING, relationFor(pair, A));
        assertEquals(Relation.INCOMING, relationFor(pair, B));
    }

    @Test void mutualRequestsCreateOneFriendshipInEitherOrder()
    {
        for (long first : new long[]{A, B})
        {
            long second = first == A ? B : A;
            Pair pair = apply(apply(empty(A, B), first, Command.REQUEST), second, Command.REQUEST);
            assertTrue(pair.friends());
            assertEquals(0, pair.requesterId());
            assertEquals(2, pair.version());
            assertEquals(Relation.FRIEND, relationFor(pair, first));
            assertEquals(Relation.FRIEND, relationFor(pair, second));
        }
    }

    @Test void repeatedSameDirectionRequestDoesNotAcceptOrBumpVersion()
    {
        Pair pair = pending();
        assertSame(pair, apply(pair, A, Command.REQUEST));
        assertFalse(pair.friends());
    }

    @Test void existingFriendRequestIsNoOp()
    {
        Pair pair = friends();
        assertSame(pair, apply(pair, A, Command.REQUEST));
        assertSame(pair, apply(pair, B, Command.REQUEST));
    }

    @Test void onlyRecipientCanAccept()
    {
        Pair pair = pending();
        assertThrows(IllegalStateException.class, () -> apply(pair, A, Command.ACCEPT));
        assertTrue(apply(pair, B, Command.ACCEPT).friends());
    }

    @Test void onlyRecipientCanReject()
    {
        Pair pair = pending();
        assertThrows(IllegalStateException.class, () -> apply(pair, A, Command.REJECT));
        Pair rejected = apply(pair, B, Command.REJECT);
        assertEquals(Relation.NONE, relationFor(rejected, A));
        assertEquals(0, rejected.requesterId());
    }

    @Test void onlyRequesterCanWithdraw()
    {
        Pair pair = pending();
        assertThrows(IllegalStateException.class, () -> apply(pair, B, Command.WITHDRAW));
        Pair withdrawn = apply(pair, A, Command.WITHDRAW);
        assertEquals(Relation.NONE, relationFor(withdrawn, B));
        assertFalse(withdrawn.friends());
    }

    @Test void noPendingRequestCannotBeAcceptedRejectedOrWithdrawn()
    {
        for (Pair pair : new Pair[]{empty(A, B), friends()})
            for (Command command : new Command[]{Command.ACCEPT, Command.REJECT, Command.WITHDRAW})
                assertThrows(IllegalStateException.class, () -> apply(pair, B, command));
    }

    @Test void eitherFriendCanDeleteAndDeleteCannotConsumeRequest()
    {
        for (long actor : new long[]{A, B})
        {
            Pair deleted = apply(friends(), actor, Command.DELETE);
            assertEquals(Relation.NONE, relationFor(deleted, A));
            assertEquals(Relation.NONE, relationFor(deleted, B));
            assertSame(deleted, apply(deleted, actor, Command.DELETE));
            assertThrows(IllegalStateException.class, () -> apply(pending(), actor, Command.DELETE));
        }
    }

    @Test void blockClearsFriendshipAndPendingRequests()
    {
        for (Pair before : new Pair[]{pending(), friends()})
            for (long actor : new long[]{A, B})
            {
                Pair blocked = apply(before, actor, Command.BLOCK);
                assertFalse(blocked.friends());
                assertEquals(0, blocked.requesterId());
                assertFalse(passesBlockCheck(blocked, A));
                assertFalse(passesBlockCheck(blocked, B));
            }
    }

    @Test void unblockNeverRestoresFriendshipOrRequest()
    {
        for (Pair before : new Pair[]{pending(), friends()})
        {
            Pair unblocked = apply(apply(before, A, Command.BLOCK), A, Command.UNBLOCK);
            assertFalse(unblocked.friends());
            assertEquals(0, unblocked.requesterId());
            assertEquals(Relation.NONE, relationFor(unblocked, A));
            assertEquals(Relation.NONE, relationFor(unblocked, B));
            assertTrue(passesBlockCheck(unblocked, A));
        }
    }

    @Test void cannotRemovePeersBlock()
    {
        Pair blocked = apply(friends(), B, Command.BLOCK);
        assertSame(blocked, apply(blocked, A, Command.UNBLOCK));
        assertFalse(passesBlockCheck(blocked, A));
    }

    @Test void twoBlocksRequireIndependentRemoval()
    {
        Pair both = apply(apply(friends(), A, Command.BLOCK), B, Command.BLOCK);
        Pair one = apply(both, A, Command.UNBLOCK);
        assertFalse(passesBlockCheck(one, A));
        assertEquals(BlacklistRelation.NONE, ownBlacklistFor(one, A, A));
        assertEquals(BlacklistRelation.BLOCKED, ownBlacklistFor(one, B, B));
        Pair neither = apply(one, B, Command.UNBLOCK);
        assertTrue(passesBlockCheck(neither, B));
        assertFalse(neither.friends());
    }

    @Test void blockIsNeverExposedInNormalRelationProjection()
    {
        Pair blocked = apply(friends(), A, Command.BLOCK);
        assertEquals(relationFor(empty(A, B), A), relationFor(blocked, A));
        assertEquals(relationFor(empty(A, B), B), relationFor(blocked, B));
        assertEquals(BlacklistRelation.BLOCKED, ownBlacklistFor(blocked, A, A));
        assertEquals(BlacklistRelation.NONE, ownBlacklistFor(blocked, B, B));
        assertThrows(IllegalArgumentException.class, () -> ownBlacklistFor(blocked, B, A));
    }

    @Test void blockedRelationshipCommandsFailWithSameNonDisclosingReason()
    {
        for (Command command : new Command[]{Command.REQUEST, Command.ACCEPT, Command.REJECT,
                Command.WITHDRAW, Command.DELETE})
        {
            Pair byMe = apply(empty(A, B), A, Command.BLOCK);
            Pair byPeer = apply(empty(A, B), B, Command.BLOCK);
            Exception first = assertThrows(IllegalStateException.class, () -> apply(byMe, A, command));
            Exception second = assertThrows(IllegalStateException.class, () -> apply(byPeer, A, command));
            assertEquals("Relationship operation unavailable", first.getMessage());
            assertEquals(first.getMessage(), second.getMessage());
        }
    }

    @Test void staleAcceptanceCannotResurrectWithdrawnRequest()
    {
        Pair original = pending();
        Pair withdrawn = apply(original, A, Command.WITHDRAW);
        Pair newRequest = apply(withdrawn, A, Command.REQUEST);
        assertThrows(IllegalStateException.class,
                () -> FriendPairRules.apply(newRequest, B, original.version(), Command.ACCEPT));
        assertFalse(newRequest.friends());
    }

    @Test void staleRequestCannotUndoBlock()
    {
        Pair original = pending();
        Pair blocked = apply(original, B, Command.BLOCK);
        assertThrows(IllegalStateException.class,
                () -> FriendPairRules.apply(blocked, A, original.version(), Command.REQUEST));
        assertEquals(BlacklistRelation.BLOCKED, ownBlacklistFor(blocked, B, B));
    }

    @Test void outsiderCannotReadOrMutatePair()
    {
        Pair pair = friends();
        for (Command command : Command.values())
            assertThrows(IllegalArgumentException.class, () -> apply(pair, 99, command));
        assertThrows(IllegalArgumentException.class, () -> relationFor(pair, 99));
        assertThrows(IllegalArgumentException.class, () -> passesBlockCheck(pair, 99));
        assertThrows(IllegalArgumentException.class, () -> ownBlacklistFor(pair, 99, 99));
    }

    @Test void malformedStateIsNotSilentlyRepaired()
    {
        assertThrows(IllegalArgumentException.class, () -> new Pair(A, B, -1, 0, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Pair(A, B, 0, 99, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Pair(A, B, 0, A, true, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Pair(A, B, 0, 0, true, true, false));
        assertThrows(IllegalArgumentException.class, () -> new Pair(A, B, 0, A, false, false, true));
        assertThrows(IllegalArgumentException.class, () -> apply(empty(A, B), A, null));
    }

    @Test void versionOverflowCannotWrap()
    {
        Pair pair = new Pair(A, B, Long.MAX_VALUE, 0, false, false, false);
        assertThrows(ArithmeticException.class, () -> apply(pair, A, Command.REQUEST));
        assertEquals(Long.MAX_VALUE, pair.version());
    }

    @Test void unblockedPairDoesNotImplyFriendshipOrDmAuthorization()
    {
        Pair pair = empty(A, B);
        assertTrue(passesBlockCheck(pair, A));
        assertEquals(Relation.NONE, relationFor(pair, A));
        assertFalse(pair.friends());
        // No follow/fan input exists: the existing follow service cannot upgrade this state.
    }
}
