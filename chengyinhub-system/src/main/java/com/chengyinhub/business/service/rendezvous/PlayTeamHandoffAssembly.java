package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Guard mark for the manager {@link PlayTeamHandoffDelegate} derived from the session.
 * {@code MANDATORY} only proves some transaction exists. Not a Spring bean and not a production switch.
 */
public final class PlayTeamHandoffAssembly {
    private static final Object HELD = new Object();

    private PlayTeamHandoffAssembly() {}

    /** Called only after {@code lockGuard} has returned inside this manager's transaction. */
    public static void hold(DataSourceTransactionManager manager) {
        Objects.requireNonNull(manager);
        if (TransactionSynchronizationManager.hasResource(manager)) {
            throw new IllegalStateException("W16_GUARD_MISSING");
        }
        TransactionSynchronizationManager.bindResource(manager, HELD);
    }

    public static void release(DataSourceTransactionManager manager) {
        if (manager != null && TransactionSynchronizationManager.hasResource(manager)) {
            TransactionSynchronizationManager.unbindResource(manager);
        }
    }

    /**
     * Caller-supplied session and manager are not the handoff chain.
     * Use {@link PlayTeamHandoffDelegate#bind}.
     */
    public static PlayTeamHandoffGateway open(SqlSessionTemplate session, DataSourceTransactionManager manager,
                                              PlayTeamHandoffGateway delegate) {
        Objects.requireNonNull(session);
        Objects.requireNonNull(manager);
        Objects.requireNonNull(delegate);
        throw new IllegalArgumentException("W16_CALLER_SUPPLIED_MANAGER_REFUSED");
    }

    static PlayTeamHandoffGateway guard(PlayTeamHandoffGateway delegate, DataSourceTransactionManager manager) {
        return new Guarded(Objects.requireNonNull(delegate), Objects.requireNonNull(manager));
    }

    private static final class Guarded implements PlayTeamHandoffGateway {
        private final PlayTeamHandoffGateway delegate;
        private final DataSourceTransactionManager manager;

        Guarded(PlayTeamHandoffGateway delegate, DataSourceTransactionManager manager) {
            this.delegate = delegate;
            this.manager = manager;
        }

        public boolean enabled() { return delegate.enabled(); }

        public RendezvousHandoffTeamPort.Lookup lookup(String requestId) { return delegate.lookup(requestId); }

        public long create(RendezvousHandoffRequest request) {
            requireHeld();
            return delegate.create(request);
        }

        public boolean join(RendezvousHandoffRequest request) {
            requireHeld();
            return delegate.join(request);
        }

        public void betweenCreateAndJoin() { delegate.betweenCreateAndJoin(); }

        private void requireHeld() {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.getResource(manager.getDataSource()) == null) {
                throw new IllegalStateException("W16_TRANSACTION_RESOURCE_MISMATCH");
            }
            if (TransactionSynchronizationManager.getResource(manager) != HELD) {
                throw new IllegalStateException("W16_GUARD_MISSING");
            }
        }
    }
}
