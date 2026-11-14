package com.chengyinhub.business.service.channel.interaction;

import java.time.Clock;
import java.util.Objects;

/**
 * Transaction orchestration only; deliberately not a registered service or HTTP handler.
 * A durable adapter and current channel authorization must exist before wiring this class.
 * This does not replace /api/im/send or its original send receipt.
 */
public final class ChainCommandExecutor {
    private final Store store;
    private final Clock clock;

    public ChainCommandExecutor(Store store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The command's member must come from the authenticated principal, never request JSON. */
    public Receipt execute(ChainCommandIdentity command) {
        Objects.requireNonNull(command, "command");
        // No catch/retry: an unknown commit outcome must be recovered using this same key.
        return store.inTransaction(transaction -> {
            Objects.requireNonNull(transaction, "transaction");
            // Replays still require current access, before reading any original result.
            transaction.requireCurrentAccess(command);
            Receipt original = transaction.findOriginal(command.chainId(), command.requestId());
            if (original != null) {
                command.matchesOriginal(original.command());
                return original;
            }
            ChainParticipationTransition.Snapshot source = Objects.requireNonNull(
                    transaction.lockCurrent(command), "current snapshot");
            if (source.chainId() != command.chainId()
                    || source.conversationId() != command.conversationId()
                    || source.memberId() != command.memberId())
                throw new IllegalStateException("Wrong chain snapshot scope");
            // Read server time after waiting for the lock, not at request arrival.
            ChainParticipationTransition.Proposal proposal = ChainParticipationTransition.calculate(
                    source, command.action(), clock.instant());
            Receipt stored = Objects.requireNonNull(transaction.persist(command, proposal), "stored receipt");
            command.matchesOriginal(stored.command());
            if (!proposal.equals(stored.proposal()))
                throw new IllegalStateException("Stored receipt does not match applied transition");
            return stored;
        });
    }

    /** Immutable original result, independent of the mutable current participation row. */
    public record Receipt(String receiptId, ChainCommandIdentity command,
            ChainParticipationTransition.Proposal proposal) {
        public Receipt {
            if (receiptId == null || receiptId.isBlank())
                throw new IllegalArgumentException("Missing durable receipt identity");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(proposal, "proposal");
            if (command.chainId() != proposal.chainId()
                    || command.conversationId() != proposal.conversationId()
                    || command.memberId() != proposal.memberId())
                throw new IllegalArgumentException("Receipt scope mismatch");
        }
    }

    @FunctionalInterface
    public interface Work {
        Receipt run(Transaction transaction);
    }

    public interface Store {
        /**
         * Run once in a real transaction; return only after commit succeeds. Every callback
         * exception must roll back count, relation, original receipt and event together.
         * Do not translate CAS/uniqueness/commit failures into successful empty receipts.
         */
        Receipt inTransaction(Work work);
    }

    public interface Transaction {
        /** Validate current membership/lifecycle/action scope using the authoritative resolver. */
        void requireCurrentAccess(ChainCommandIdentity command);

        /** Read immutable command history, never infer a receipt from the current relation. */
        Receipt findOriginal(long chainId, String requestId);

        /** Lock and revalidate current chain, relation and scope until transaction completion. */
        ChainParticipationTransition.Snapshot lockCurrent(ChainCommandIdentity command);

        /**
         * Persist count, relation, immutable receipt and event atomically. Enforce both expected
         * versions, expected count, unique chain/member and unique chain/request identities.
         * NO_CHANGE still needs an original receipt. A concurrent request-key collision must
         * abort the transaction; the next same-key request reads the winner's original result.
         * Return the stored result, not an optimistic success assembled before the write.
         */
        Receipt persist(ChainCommandIdentity command, ChainParticipationTransition.Proposal proposal);
    }
}
