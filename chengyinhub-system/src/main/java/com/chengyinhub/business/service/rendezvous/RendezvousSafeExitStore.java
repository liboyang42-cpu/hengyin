package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.ExitRow;
import java.util.Objects;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exit ledger joined to the consent guard. Not a Spring bean.
 * It does not open a transaction. Writes run on the consent store's
 * {@code SqlSessionTemplate} and {@code DataSourceTransactionManager}. */
public final class RendezvousSafeExitStore {
    public enum Mode { DISABLED, ISOLATED_PERSISTENCE_EVALUATION }
    private final Mode mode;
    private final RendezvousSafeExitMapper mapper;
    private final DataSourceTransactionManager manager;

    public RendezvousSafeExitStore() {
        mode = Mode.DISABLED;
        mapper = null;
        manager = null;
    }

    public RendezvousSafeExitStore(Mode mode, SqlSessionTemplate session, DataSourceTransactionManager manager) {
        this(mode, session, manager, null);
    }

    /** {@code mapperOverride} lets a test fail a write without a second transaction. */
    RendezvousSafeExitStore(Mode mode, SqlSessionTemplate session, DataSourceTransactionManager manager,
                            RendezvousSafeExitMapper mapperOverride) {
        this.mode = Objects.requireNonNull(mode);
        if (mode != Mode.ISOLATED_PERSISTENCE_EVALUATION) throw new IllegalArgumentException("W16_EXIT_PERSISTENCE_DISABLED");
        Objects.requireNonNull(session);
        this.manager = Objects.requireNonNull(manager);
        Environment environment = session.getSqlSessionFactory().getConfiguration().getEnvironment();
        if (environment.getDataSource() != manager.getDataSource()
                || !(environment.getTransactionFactory() instanceof SpringManagedTransactionFactory)) {
            throw new IllegalArgumentException("W16_TRANSACTION_RESOURCE_MISMATCH");
        }
        mapper = mapperOverride != null ? mapperOverride : session.getMapper(RendezvousSafeExitMapper.class);
    }

    ExitRow find(String ticketId) {
        bound();
        return mapper.find(ticketId);
    }

    void insert(ExitRow row) {
        bound();
        if (mapper.insert(Objects.requireNonNull(row)) != 1) throw new IllegalStateException("W16_STALE_OR_INVALID_WRITE");
    }

    private void bound() {
        if (mode != Mode.ISOLATED_PERSISTENCE_EVALUATION || mapper == null || manager == null) {
            throw new IllegalStateException("W16_EXIT_PERSISTENCE_DISABLED");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.getResource(manager.getDataSource()) == null) {
            throw new IllegalStateException("W16_CONSENT_TRANSACTION_NOT_BOUND");
        }
    }
}
