package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import java.util.function.Function;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/** Durable pilot adapter. No Spring bean/controller/production mode is registered.
 * A database guard row serializes consent operations across JVMs. Correctness takes
 * priority over throughput for this bounded pilot; sharding requires a new lock contract.
 */
public final class MyBatisRendezvousConsentStore implements Store {
    public enum Mode { DISABLED, ISOLATED_PERSISTENCE_EVALUATION }
    private final Mode mode;
    private final RendezvousConsentMapper mapper;
    private final TransactionTemplate transactions;
    public MyBatisRendezvousConsentStore() { mode = Mode.DISABLED; mapper = null; transactions = null; }
    public MyBatisRendezvousConsentStore(Mode mode, SqlSessionTemplate session, DataSourceTransactionManager manager) {
        this.mode = Objects.requireNonNull(mode);
        Objects.requireNonNull(session); Objects.requireNonNull(manager);
        org.apache.ibatis.mapping.Environment environment = session.getSqlSessionFactory().getConfiguration().getEnvironment();
        if (environment.getDataSource() != manager.getDataSource()
                || !(environment.getTransactionFactory() instanceof SpringManagedTransactionFactory)) {
            throw new IllegalArgumentException("W16_TRANSACTION_RESOURCE_MISMATCH");
        }
        mapper = session.getMapper(RendezvousConsentMapper.class);
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(10);
    }
    public <T> T transaction(Function<Transaction, T> work) {
        if (mode != Mode.ISOLATED_PERSISTENCE_EVALUATION) throw new IllegalStateException("W16_PERSISTENCE_DISABLED");
        // Service rejections can be raised after committed cleanup; joining an outer transaction
        // would silently turn that cleanup back into a rollback. Never nest this boundary.
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("W16_NESTED_TRANSACTION_FORBIDDEN");
        Objects.requireNonNull(work);
        return transactions.execute(status -> {
            if (!Integer.valueOf(1).equals(mapper.lockGuard())) throw new IllegalStateException("W16_GUARD_MISSING");
            Session access = new Session();
            try { return work.apply(access); } finally { access.open = false; }
        });
    }
    private final class Session implements Transaction {
        private final Thread owner = Thread.currentThread();
        private boolean open = true;
        private void check() {
            if (!open || owner != Thread.currentThread() || !TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("W16_TRANSACTION_ACCESS_EXPIRED");
            }
        }
        public Ticket activeTicket(long actor) { check(); RendezvousConsentMapper.TicketRow row = mapper.activeTicket(actor); return row == null ? null : row.value(); }
        public Ticket ticket(String id) { check(); RendezvousConsentMapper.TicketRow row = mapper.ticket(id); return row == null ? null : row.value(); }
        public Match match(String id) { check(); RendezvousConsentMapper.MatchRow row = mapper.match(id); return row == null ? null : row.value(); }
        public void save(Ticket ticket) {
            check(); Objects.requireNonNull(ticket);
            Ticket before = ticket(ticket.id);
            if (before == null) changed(mapper.insertTicket(ticket));
            else changed(mapper.updateTicket(ticket));
            String reserved = mapper.reservation(ticket.id);
            boolean needsReservation = ticket.state == TicketState.RESERVED || ticket.state == TicketState.ACCEPTED;
            if (needsReservation) {
                if (ticket.matchId == null) throw new IllegalStateException("W16_RESERVATION_REQUIRED");
                if (reserved == null) changed(mapper.insertReservation(ticket.id, ticket.matchId));
                else if (!reserved.equals(ticket.matchId)) throw new IllegalStateException("W16_TICKET_ALREADY_RESERVED");
            } else if (reserved != null) {
                if (!reserved.equals(ticket.matchId)) throw new IllegalStateException("W16_RESERVATION_MISMATCH");
                changed(mapper.deleteReservation(ticket.id, reserved));
            }
        }
        public void save(Match match) {
            check(); Objects.requireNonNull(match);
            if (match.firstTicket.equals(match.secondTicket)) throw new IllegalStateException("W16_DISTINCT_TICKETS_REQUIRED");
            String first = mapper.reservation(match.firstTicket), second = mapper.reservation(match.secondTicket);
            if (match.active()) {
                if (!match.id.equals(first) || !match.id.equals(second)) throw new IllegalStateException("W16_BILATERAL_RESERVATION_REQUIRED");
            } else if (first != null || second != null) throw new IllegalStateException("W16_RESERVATION_NOT_RELEASED");
            Match before = match(match.id);
            if (before == null) {
                if (match.version != 1) throw new IllegalStateException("W16_INITIAL_VERSION_REQUIRED");
                changed(mapper.insertMatch(match));
            } else changed(mapper.updateMatch(match));
        }
    }
    private static void changed(int count) { if (count != 1) throw new IllegalStateException("W16_STALE_OR_INVALID_WRITE"); }
}
