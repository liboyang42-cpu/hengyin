package com.chengyinhub.business.domain.friend;

/**
 * Pure pair transitions for the friend contract. This is not a persistence service or an API DTO.
 * A caller must load an authoritative pair in a transaction, authenticate actorId, and atomically
 * persist the transition against the loaded version. Computing a result does not commit a command.
 * Follow/fan edges deliberately are not inputs: they never establish a friendship.
 */
public final class FriendPairRules
{
    private FriendPairRules() { }

    public enum Command { REQUEST, ACCEPT, REJECT, WITHDRAW, DELETE, BLOCK, UNBLOCK }
    public enum Relation { NONE, OUTGOING, INCOMING, FRIEND }
    public enum BlacklistRelation { NONE, BLOCKED }

    /** Internal authoritative state only; never return this object from a controller. */
    public record Pair(long lowId, long highId, long version, long requesterId,
                       boolean friends, boolean lowBlocked, boolean highBlocked)
    {
        public Pair
        {
            if (lowId <= 0 || highId <= lowId || version < 0
                    || (requesterId != 0 && requesterId != lowId && requesterId != highId)
                    || (friends && requesterId != 0)
                    || ((lowBlocked || highBlocked) && (friends || requesterId != 0)))
                throw new IllegalArgumentException("Invalid friend pair");
        }
    }

    public static Pair empty(long firstId, long secondId)
    {
        return new Pair(Math.min(firstId, secondId), Math.max(firstId, secondId),
                0, 0, false, false, false);
    }

    public static Pair apply(Pair pair, long actorId, long expectedVersion, Command command)
    {
        requireParticipant(pair, actorId);
        if (command == null) throw new IllegalArgumentException("Command required");
        if (expectedVersion != pair.version()) throw new IllegalStateException("Friend pair changed");
        boolean actorLow = actorId == pair.lowId();
        boolean lowBlocked = pair.lowBlocked();
        boolean highBlocked = pair.highBlocked();
        long requester = pair.requesterId();
        boolean friends = pair.friends();

        // Only the actor's block edge may be changed. Removing one edge cannot restore anything.
        if (command == Command.BLOCK || command == Command.UNBLOCK)
        {
            if (actorLow) lowBlocked = command == Command.BLOCK;
            else highBlocked = command == Command.BLOCK;
            if (command == Command.BLOCK) { requester = 0; friends = false; }
        }
        else
        {
            // Do not expose which participant blocked, or whether a block exists, in errors.
            if (lowBlocked || highBlocked) throw new IllegalStateException("Relationship operation unavailable");
            switch (command)
            {
                case REQUEST:
                    if (!friends)
                    {
                        if (requester == 0) requester = actorId;
                        else if (requester != actorId) { requester = 0; friends = true; }
                    }
                    break;
                case ACCEPT:
                case REJECT:
                    if (requester == 0 || requester == actorId)
                        throw new IllegalStateException("No incoming friend request");
                    requester = 0;
                    friends = command == Command.ACCEPT;
                    break;
                case WITHDRAW:
                    if (requester != actorId) throw new IllegalStateException("No outgoing friend request");
                    requester = 0;
                    break;
                case DELETE:
                    // Deleting a friendship must not silently withdraw/reject a pending request.
                    if (requester != 0) throw new IllegalStateException("Not a friendship");
                    friends = false;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported command");
            }
        }
        if (requester == pair.requesterId() && friends == pair.friends()
                && lowBlocked == pair.lowBlocked() && highBlocked == pair.highBlocked()) return pair;
        return new Pair(pair.lowId(), pair.highId(), Math.addExact(pair.version(), 1),
                requester, friends, lowBlocked, highBlocked);
    }

    /** Normal list projection contains no BLOCKED state, even for the blocker. */
    public static Relation relationFor(Pair pair, long viewerId)
    {
        requireParticipant(pair, viewerId);
        if (pair.lowBlocked() || pair.highBlocked()) return Relation.NONE;
        if (pair.friends()) return Relation.FRIEND;
        if (pair.requesterId() == 0) return Relation.NONE;
        return pair.requesterId() == viewerId ? Relation.OUTGOING : Relation.INCOMING;
    }

    /** Caller must authenticate listOwnerId; a peer cannot inspect another person's blacklist. */
    public static BlacklistRelation ownBlacklistFor(Pair pair, long viewerId, long listOwnerId)
    {
        requireParticipant(pair, viewerId);
        if (viewerId != listOwnerId) throw new IllegalArgumentException("Own blacklist required");
        boolean ownBlock = viewerId == pair.lowId() ? pair.lowBlocked() : pair.highBlocked();
        return ownBlock ? BlacklistRelation.BLOCKED : BlacklistRelation.NONE;
    }

    /** Negative prerequisite only. True does NOT authorize DM: other admission rules still apply. */
    public static boolean passesBlockCheck(Pair pair, long actorId)
    {
        requireParticipant(pair, actorId);
        return !pair.lowBlocked() && !pair.highBlocked();
    }

    private static void requireParticipant(Pair pair, long actorId)
    {
        if (pair == null || (actorId != pair.lowId() && actorId != pair.highId()))
            throw new IllegalArgumentException("Friend pair participant required");
    }
}
