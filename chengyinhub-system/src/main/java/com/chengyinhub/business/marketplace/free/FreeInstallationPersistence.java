package com.chengyinhub.business.marketplace.free;

import com.chengyinhub.business.marketplace.free.store.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.util.Objects;

/** Construction checks prevent a second datasource/session or autocommit entitlement lookup. */
final class FreeInstallationPersistence {
    final DataSource dataSource;
    private final SqlSessionTemplate session;
    private final DataSourceTransactionManager manager;
    final FreeModuleClaimStore claims;
    final FreeInstallTargetStore targets;
    FreeInstallationPersistence(SqlSessionTemplate session, DataSourceTransactionManager manager) {
        this.session=Objects.requireNonNull(session); this.manager=Objects.requireNonNull(manager);
        dataSource=Objects.requireNonNull(manager.getDataSource());
        org.apache.ibatis.mapping.Environment environment=session.getSqlSessionFactory().getConfiguration().getEnvironment();
        require(environment.getDataSource()==dataSource && environment.getTransactionFactory() instanceof SpringManagedTransactionFactory,
                "INSTALL_DATASOURCE_MISMATCH");
        claims=session.getMapper(FreeModuleClaimStore.class); targets=session.getMapper(FreeInstallTargetStore.class);
    }
    /** Only exact mapper instances created by the verified composition root can participate. */
    void requireMapper(Object mapper,Object knownMapper,PlatformTransactionManager transactionManager) {
        require(transactionManager==manager && manager.getDataSource()==dataSource,"INSTALL_DATASOURCE_MISMATCH");
        require(mapper!=null && mapper==knownMapper,"INSTALL_MAPPER_MISMATCH");
        requireEnvironment();
    }
    private void requireEnvironment() {
        org.apache.ibatis.mapping.Environment environment=session.getSqlSessionFactory().getConfiguration().getEnvironment();
        require(manager.getDataSource()==dataSource && environment.getDataSource()==dataSource
                && environment.getTransactionFactory() instanceof SpringManagedTransactionFactory,"INSTALL_DATASOURCE_MISMATCH");
    }
    void requireTransaction() {
        requireEnvironment();
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.hasResource(dataSource)
                && Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()),
                "INSTALL_TRANSACTION_REQUIRED");
    }
    void lockBuyer(long actor) {require(actor>0 && Long.valueOf(actor).equals(claims.lockActiveMember(actor)),"BUYER_SCOPE_DENIED");}
    static void require(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
}
