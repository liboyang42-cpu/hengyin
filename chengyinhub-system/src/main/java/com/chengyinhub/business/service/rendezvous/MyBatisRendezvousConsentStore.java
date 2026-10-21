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

/** Durable consent store. Not a Spring bean; {@link RendezvousPairingComposition} builds it.
 * {@link Mode#FORMAL} and {@link Mode#ISOLATED_PERSISTENCE_EVALUATION} both run this mapper
 * inside the caller's transaction manager. {@link Mode#DISABLED} refuses every call.
 * A guard row serializes consent operations. Sharding needs a new lock contract.
 */
public final class MyBatisRendezvousConsentStore implements Store {
    public enum Mode { DISABLED, ISOLATED_PERSISTENCE_EVALUATION, FORMAL }
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
        if (mode != Mode.ISOLATED_PERSISTENCE_EVALUATION && mode != Mode.FORMAL) throw new IllegalStateException("W16_PERSISTENCE_DISABLED");
        // Service rejections can be raised after committed cleanup; joining an outer transaction
        // would silently turn that cleanup back into a rollback. Never nest this boundary.
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("W16_NESTED_TRANSACTION_FORBIDDEN");
        Objects.requireNonNull(work);
        return transactions.execute(status -> {
            if (!Integer.valueOf(1).equals(mapper.lockGuard())) throw new IllegalStateException("W16_GUARD_MISSING");
            RendezvousConsentSession access = new RendezvousConsentSession(mapper);
            try { return work.apply(access); } finally { access.close(); }
        });
    }
}
