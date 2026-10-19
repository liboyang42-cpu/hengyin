package com.chengyinhub.business.marketplace.paid;

import com.chengyinhub.business.marketplace.paid.store.PaidLicenseStore;
import com.chengyinhub.business.marketplace.paid.read.PurchasedLicenseIndexStore;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;

/** Closed W18 session/transaction pair. External Configuration/manager mutation cannot redirect SQL
 * outside the transaction. Existing exact mapped statements and interceptor instances are retained;
 * no tenant/security interceptor is silently dropped. No factory/manager/session is exposed. */
final class PaidPersistenceBoundary {
    private final DataSource dataSource;
    private final Configuration sourceConfiguration;
    private final Environment sourceEnvironment;
    private final DataSourceTransactionManager sourceManager;
    private final List<Interceptor> sourceInterceptors;
    private final Map<String,MappedStatement> statements=new LinkedHashMap<String,MappedStatement>();
    private final SqlSessionTemplate privateSession;
    private final TransactionTemplate transactions;
    private final DataSourceTransactionManager fixedManager;
    private final Object fenceKey=new Object();
    PaidPersistenceBoundary(SqlSessionTemplate source,DataSourceTransactionManager manager) {
        Objects.requireNonNull(source);sourceManager=Objects.requireNonNull(manager);
        sourceConfiguration=source.getSqlSessionFactory().getConfiguration();sourceEnvironment=sourceConfiguration.getEnvironment();
        dataSource=Objects.requireNonNull(manager.getDataSource());
        sourceInterceptors=new ArrayList<Interceptor>(sourceConfiguration.getInterceptors());
        require(sourceEnvironment!=null && sourceEnvironment.getDataSource()==dataSource
                && sourceEnvironment.getTransactionFactory() instanceof SpringManagedTransactionFactory);
        Configuration closed=new Configuration();
        closed.setEnvironment(new Environment("w18-paid-fixed",new SpringManagedTransactionFactory(),dataSource));
        closed.setLocalCacheScope(LocalCacheScope.STATEMENT);closed.setCacheEnabled(false);
        for(Interceptor interceptor:sourceInterceptors)closed.addInterceptor(interceptor);
        for(Class<?> mapper:Arrays.<Class<?>>asList(PaidLicenseStore.class,PurchasedLicenseIndexStore.class,PaidInstallCommandStore.class,PaidProfessionalTargetStore.class,PaidInstalledTextStore.class,PaidProfessionalMaterializationStore.class,PaidProfessionalTemplateStore.class,PaidProfessionalOperationStore.class,
                com.chengyinhub.business.marketplace.reservation.store.ModuleReservationStore.class,
                com.chengyinhub.business.marketplace.reservation.store.ModuleInstallationStore.class,
                com.chengyinhub.business.marketplace.free.store.FreeInstallTargetStore.class)) {
            if(!sourceConfiguration.hasMapper(mapper))continue;
            closed.addMapper(mapper);
            for(String id:new ArrayList<String>(sourceConfiguration.getMappedStatementNames())) {
                if(!id.startsWith(mapper.getName()+"."))continue;
                MappedStatement statement=sourceConfiguration.getMappedStatement(id,false);
                statements.put(id,statement);closed.addMappedStatement(statement);
            }
        }
        require(!statements.isEmpty());verifyConfiguration();
        privateSession=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(closed));
        // A private manager is essential: checking an external manager and then using that same
        // mutable instance leaves a check→begin race which can autocommit SQL on another datasource.
        fixedManager=new DataSourceTransactionManager(dataSource);
        transactions=new TransactionTemplate(fixedManager);transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transactions.setTimeout(10);
    }
    <T> T mapper(Class<T> type){return privateSession.getMapper(type);}
    void verifyConfiguration(){
        require(sourceManager.getDataSource()==dataSource && sourceConfiguration.getEnvironment()==sourceEnvironment
                && sourceEnvironment.getDataSource()==dataSource && sourceEnvironment.getTransactionFactory() instanceof SpringManagedTransactionFactory);
        List<Interceptor> current=sourceConfiguration.getInterceptors();require(current.size()==sourceInterceptors.size());
        for(int i=0;i<current.size();i++)require(current.get(i)==sourceInterceptors.get(i));
        for(Map.Entry<String,MappedStatement> item:statements.entrySet())
            require(sourceConfiguration.hasStatement(item.getKey(),false) && sourceConfiguration.getMappedStatement(item.getKey(),false)==item.getValue());
    }
    void requireTransaction(){
        verifyConfiguration();
        require(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.hasResource(dataSource)
                && Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()));
    }
    /** Transaction-local source proofs must not be replayed in a later transaction on the same DS. */
    Object currentTransactionIdentity(){requireTransaction();return TransactionSynchronizationManager.getResource(dataSource);}
    void requireTransactionIdentity(Object identity){requireTransaction();require(identity!=null&&identity==TransactionSynchronizationManager.getResource(dataSource));}
    void fenceCurrentTransaction(){
        requireTransaction();
        if(TransactionSynchronizationManager.hasResource(fenceKey))return;
        TransactionSynchronizationManager.bindResource(fenceKey,Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){requireTransaction();}
            @Override public void suspend(){TransactionSynchronizationManager.unbindResourceIfPossible(fenceKey);}
            @Override public void resume(){TransactionSynchronizationManager.bindResource(fenceKey,Boolean.TRUE);}
            @Override public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(fenceKey);}
        });
    }
    void requireStore(Object supplied,Object expected,org.springframework.transaction.PlatformTransactionManager manager){
        verifyConfiguration();require(supplied==expected && manager==fixedManager);
    }
    com.chengyinhub.business.marketplace.reservation.ModuleInstallReservationService reservationService(
            com.chengyinhub.business.marketplace.reservation.store.ModuleReservationStore store,
            com.chengyinhub.business.marketplace.reservation.ReservationAuthority authority,java.time.Clock clock){
        verifyConfiguration();return new com.chengyinhub.business.marketplace.reservation.ModuleInstallReservationService(store,authority,fixedManager,clock);
    }
    com.chengyinhub.business.marketplace.reservation.ModuleTextDraftInstallationService installationService(
            com.chengyinhub.business.marketplace.reservation.store.ModuleReservationStore reservations,
            com.chengyinhub.business.marketplace.reservation.store.ModuleInstallationStore installs,
            com.chengyinhub.business.marketplace.reservation.InstallationAuthority authority,java.time.Clock clock){
        verifyConfiguration();return new com.chengyinhub.business.marketplace.reservation.ModuleTextDraftInstallationService(reservations,installs,authority,fixedManager,clock);
    }
    <T> T inTransaction(Supplier<T> operation){
        verifyConfiguration();
        return transactions.execute(status->{fenceCurrentTransaction();T result=operation.get();requireTransaction();return result;});
    }
    private static void require(boolean value){if(!value)throw new IllegalArgumentException("Workshop paid persistence configuration changed");}
}
