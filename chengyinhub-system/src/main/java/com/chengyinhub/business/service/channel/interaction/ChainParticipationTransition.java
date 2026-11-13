package com.chengyinhub.business.service.channel.interaction;

import java.time.Instant;
import java.util.Objects;

/**
 * Pure transition calculation, not a persistence implementation or authorization grant.
 * The caller must authorize current conversation access and lock/revalidate both rows.
 * Apply count, relation, command receipt and event together in one transaction, guarded by
 * both expected versions and unique (chain_id, member_id)/(chain_id, request_id).
 * A failed CAS/unique constraint must roll back the whole transaction; recalculate only
 * after reading its original request receipt. Never publish the proposal as success.
 */
public final class ChainParticipationTransition {
    private ChainParticipationTransition() { }

    public enum Action { JOIN, WITHDRAW }
    public enum EntryState { ABSENT, ACTIVE, WITHDRAWN }
    public enum Effect { NO_CHANGE, INSERT_RELATION, REACTIVATE_RELATION, WITHDRAW_RELATION }

    public record Snapshot(long chainId, long conversationId, long memberId,
            int maxSlots, int activeCount, boolean open, Instant deadline,
            long chainVersion, long entryVersion, EntryState entryState) {
        public Snapshot {
            if (chainId <= 0 || conversationId <= 0 || memberId <= 0 || maxSlots <= 0
                    || activeCount < 0 || activeCount > maxSlots || chainVersion < 0
                    || entryVersion < 0) throw new IllegalArgumentException("Invalid chain snapshot");
            Objects.requireNonNull(entryState, "entryState");
            Objects.requireNonNull(deadline, "deadline");
            if (entryState == EntryState.ACTIVE && activeCount == 0)
                throw new IllegalArgumentException("Active relation without occupied slot");
        }
    }

    public record Proposal(long chainId, long conversationId, long memberId,
            long expectedChainVersion, long expectedEntryVersion,
            int expectedActiveCount, int nextActiveCount, Effect effect) { }

    public static Proposal calculate(Snapshot source, Action action, Instant serverNow) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(serverNow, "serverNow");
        Effect effect;
        int count = source.activeCount();
        if (action == Action.WITHDRAW) {
            // Repeated withdrawal cannot release the slot a second time.
            effect = source.entryState() == EntryState.ACTIVE
                    ? Effect.WITHDRAW_RELATION : Effect.NO_CHANGE;
            if (effect == Effect.WITHDRAW_RELATION) count--;
        } else if (source.entryState() == EntryState.ACTIVE) {
            effect = Effect.NO_CHANGE;
        } else {
            if (!source.open() || !serverNow.isBefore(source.deadline()))
                throw new IllegalStateException("Chain is closed or expired");
            if (count >= source.maxSlots()) throw new IllegalStateException("Chain is full");
            count++;
            effect = source.entryState() == EntryState.ABSENT
                    ? Effect.INSERT_RELATION : Effect.REACTIVATE_RELATION;
        }
        return new Proposal(source.chainId(), source.conversationId(), source.memberId(),
                source.chainVersion(), source.entryVersion(), source.activeCount(), count, effect);
    }
}
