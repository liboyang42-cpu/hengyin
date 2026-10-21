package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Match;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Ticket;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.ExitRow;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.Store;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.Tx;
import java.util.Objects;
import java.util.function.Function;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable exit ledger. No Spring bean and no production mode is registered.
 * Consent ticket and match methods fail closed: this store does not write A's reservation tables. */
public final class RendezvousSafeExitStore implements Store {
    public enum Mode { DISABLED, ISOLATED_PERSISTENCE_EVALUATION }
    private final Mode mode;
    private final RendezvousSafeExitMapper mapper;
    private final TransactionTemplate transactions;

    public RendezvousSafeExitStore() { this.mode = Mode.DISABLED; this.mapper = null; this.transactions = null; }

    public RendezvousSafeExitStore(Mode mode, SqlSessionTemplate session, DataSourceTransactionManager manager) {
        this.mode = Objects.requireNonNull(mode);
        Objects.requireNonNull(session);
        Objects.requireNonNull(manager);
        org.apache.ibatis.mapping.Environment environment = session.getSqlSessionFactory().getConfiguration().getEnvironment();
        if (environment.getDataSource() != manager.getDataSource()
                || !(environment.getTransactionFactory() instanceof SpringManagedTransactionFactory)) {
            throw new IllegalArgumentException("W16_TRANSACTION_RESOURCE_MISMATCH");
        }
        mapper = session.getMapper(RendezvousSafeExitMapper.class);
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(10);
    }

    public <T> T transaction(Function<Tx, T> work) {
        if (mode != Mode.ISOLATED_PERSISTENCE_EVALUATION) throw new IllegalStateException("W16_EXIT_PERSISTENCE_DISABLED");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("W16_NESTED_TRANSACTION_FORBIDDEN");
        Objects.requireNonNull(work);
        return transactions.execute(status -> {
            if (!Integer.valueOf(1).equals(mapper.lockGuard())) throw new IllegalStateException("W16_EXIT_GUARD_MISSING");
            Session session = new Session(mapper);
            try { return work.apply(session); }
            finally { session.close(); }
        });
    }

    private static final class Session implements Tx {
        private final RendezvousSafeExitMapper mapper;
        private final Thread owner = Thread.currentThread();
        private boolean open = true;
        Session(RendezvousSafeExitMapper mapper) { this.mapper = mapper; }
        void close() { open = false; }
        private void check() {
            if (!open || owner != Thread.currentThread() || !TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("W16_TRANSACTION_ACCESS_EXPIRED");
            }
        }
        public ExitRow find(String ticketId) {
            check();
            return mapper.find(ticketId);
        }
        public void insert(ExitRow row) {
            check();
            changed(mapper.insert(Objects.requireNonNull(row)));
        }
        public void markLaunch(ExitRow row) {
            check();
            changed(mapper.markLaunch(Objects.requireNonNull(row)));
        }
        public Ticket activeTicket(long actor) { throw unbound(); }
        public Ticket ticket(String id) { throw unbound(); }
        public Match match(String id) { throw unbound(); }
        public void save(Ticket ticket) { throw unbound(); }
        public void save(Match match) { throw unbound(); }
        private static IllegalStateException unbound() { return new IllegalStateException("W16_CONSENT_TRANSACTION_NOT_BOUND"); }
        private static void changed(int count) {
            if (count != 1) throw new IllegalStateException("W16_STALE_OR_INVALID_WRITE");
        }
    }
}
