package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.mapper.ImBlockMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/**
 * Authoritative {@code im_block} write and current-match stop in one consent guard
 * transaction. Not a Spring bean and not a replacement for the global IM service.
 * Does not create a block table, dissolve a team, or delete a chat.
 *
 * <p>The terminate port defaults fail-closed ({@code W16_MATCH_TERMINATION_UNWIRED}).
 * {@link #guardStop()} is only the existing package-private pair stop used by the
 * isolated adapter. It is not A's frozen signature. {@code ImServiceImpl.block} is
 * not called from here.
 */
public final class RendezvousBlockIntegration {
    private final Store store;
    private final ImBlockMapper blocks;
    private final RendezvousBlockIntegrationTermination termination;

    public RendezvousBlockIntegration(MyBatisRendezvousConsentStore.Mode mode, SqlSessionTemplate session,
            DataSourceTransactionManager manager, RendezvousBlockIntegrationTermination termination) {
        this(new MyBatisRendezvousConsentStore(mode, session, manager), session.getMapper(ImBlockMapper.class), termination);
    }

    public RendezvousBlockIntegration(Store store, ImBlockMapper blocks, RendezvousBlockIntegrationTermination termination) {
        this.store = Objects.requireNonNull(store);
        this.blocks = Objects.requireNonNull(blocks);
        this.termination = Objects.requireNonNull(termination);
    }

    /** Absent A binding. Throws inside the guard transaction so the block insert rolls back. */
    public static RendezvousBlockIntegrationTermination unwired() {
        return (tx, actor, other) -> {
            throw new IllegalStateException("W16_MATCH_TERMINATION_UNWIRED");
        };
    }

    /** In-tree pair stop. Not {@code terminateCurrentMatch} and not {@code verifyParticipants}. */
    public static RendezvousBlockIntegrationTermination guardStop() {
        return (tx, actor, other) -> stopBlockedPair(tx, actor, other);
    }

    public void block(ImBlock block) {
        Objects.requireNonNull(block);
        Long first = block.getMemberId(), second = block.getBlockedMemberId();
        if (first == null || second == null || first.equals(second)) {
            throw new IllegalArgumentException("W16_INVALID_BLOCK");
        }
        ImBlock persisted = new ImBlock();
        persisted.setMemberId(first);
        persisted.setBlockedMemberId(second);
        store.transaction(tx -> {
            blocks.insertImBlock(persisted);
            if (blocks.selectOne(first, second) == null || blocks.existsBetween(first, second) <= 0) {
                throw new IllegalStateException("W16_BLOCK_NOT_PERSISTED");
            }
            String shared = sharedActiveMatch(tx, first, second);
            termination.terminateCurrentMatch(tx, first, second);
            if (shared != null) requireStopped(tx, shared);
            return null;
        });
    }

    /** Deletes only this directional block row. Does not write tickets or matches. */
    public void unblock(long actor, long other) {
        if (actor <= 0 || other <= 0 || actor == other) throw new IllegalArgumentException("W16_INVALID_BLOCK");
        store.transaction(tx -> {
            Objects.requireNonNull(tx);
            blocks.deleteImBlock(actor, other);
            return null;
        });
    }

    private static String sharedActiveMatch(Transaction tx, long first, long second) {
        Ticket a = tx.activeTicket(first), b = tx.activeTicket(second);
        if (a == null || b == null || a.matchId == null || !a.matchId.equals(b.matchId)) return null;
        Match match = tx.match(a.matchId);
        if (match == null || !match.active()) return null;
        boolean pair = (match.firstTicket.equals(a.id) && match.secondTicket.equals(b.id))
                || (match.firstTicket.equals(b.id) && match.secondTicket.equals(a.id));
        if (!pair) throw new Rejection("CORRUPT_RESERVATION");
        return match.id;
    }

    private static void requireStopped(Transaction tx, String matchId) {
        Match match = tx.match(matchId);
        if (match == null || match.state != MatchState.SAFETY_STOPPED) {
            throw new IllegalStateException("W16_MATCH_NOT_TERMINATED");
        }
        Ticket a = tx.ticket(match.firstTicket), b = tx.ticket(match.secondTicket);
        if (a == null || b == null || a.active() || b.active()) {
            throw new IllegalStateException("W16_MATCH_NOT_TERMINATED");
        }
    }
}
