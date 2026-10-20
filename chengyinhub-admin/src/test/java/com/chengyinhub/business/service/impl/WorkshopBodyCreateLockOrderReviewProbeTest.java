package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.marketplace.paid.*;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Regression observes the same operation-row barrier on both old and repaired code.
 * Original reviewer buyer-barrier bytes are retained separately as evidence. No SQL/result substitution. */
class WorkshopBodyCreateLockOrderReviewProbeTest {
    @Test void concurrentProtectedBodyAndCreateUseOneConsistentBuyerAndReservationLockOrder() throws Exception {
        WorkshopPaidLicenseTransactionTest f=new WorkshopPaidLicenseTransactionTest();f.setup();
        ReflectionTestUtils.invokeMethod(f,"materializationSetup");
        PaidProfessionalMaterializationCommand command=ReflectionTestUtils.invokeMethod(f,"materialization");
        SqlSessionTemplate session=(SqlSessionTemplate)ReflectionTestUtils.getField(f,"session");
        DataSourceTransactionManager manager=(DataSourceTransactionManager)ReflectionTestUtils.getField(f,"manager");
        Clock clock=(Clock)ReflectionTestUtils.getField(f,"installClock");
        Barrier barrier=new Barrier(manager.getDataSource());
        // Install the observational barrier before constructing the two reviewed boundaries.
        // Previously constructed fixture services are no longer invoked after this point.
        session.getConfiguration().addInterceptor(barrier);
        PaidInstalledTextReader body=new PaidInstalledTextReader(session,manager,clock,PaidInstalledTextReader.Policy.OWNER_PAID_COMMITTED_TEXT_READ_V1);
        PaidProfessionalOperationService create=new PaidProfessionalOperationService(session,manager,clock,PaidProfessionalMaterializationService.Policy.REVIEWED_GUARDED_CMS_TEXT_V1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> creation=pool.submit(()->{Thread.currentThread().setName("w18-review-create");return create.submit(7,command);});
            assertTrue(barrier.operationHeld.await(10,TimeUnit.SECONDS),"Create must hold its operation row in the second physical transaction");
            Future<?> reading=pool.submit(()->{Thread.currentThread().setName("w18-review-body");return body.detail(7,command.installationRequestId,command.ownedDraftId);});
            assertTrue(barrier.bodyReachesBuyer.await(10,TimeUnit.SECONDS),"Body must reach its account check while create is paused at its operation row");
            barrier.resumeCreate.countDown();
            List<String> failures=new ArrayList<String>();
            for(Future<?> result:Arrays.asList(creation,reading)){
                try{result.get(20,TimeUnit.SECONDS);}catch(ExecutionException e){failures.add(e.getCause().getClass().getSimpleName()+":"+String.valueOf(e.getCause().getMessage()));}
            }
            System.out.println("W18_REVIEW_LOCK_ORDER create_held_operation=true body_reached_buyer=true failed_calls="+failures.size());
            for(String failure:failures)System.out.println("W18_REVIEW_LOCK_FAILURE "+failure);
            assertTrue(failures.isEmpty(),"Ordinary concurrent protected read and creation should serialize without a buyer/request-mutex cycle: "+failures);
            assertEquals("CREATED",((Map<?,?>)creation.get()).get("state"));
            assertEquals("w18-paid-installed-text-v1",((Map<?,?>)reading.get()).get("schema"));
            org.springframework.jdbc.core.JdbcTemplate jdbc=(org.springframework.jdbc.core.JdbcTemplate)ReflectionTestUtils.getField(f,"jdbc");
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cms_member_template",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM w18_cms_template_provenance",Integer.class));
        } finally {barrier.resumeCreate.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
    }
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    public static final class Barrier implements Interceptor {
        final Object source;Object preparing;
        final CountDownLatch operationHeld=new CountDownLatch(1),bodyReachesBuyer=new CountDownLatch(1),resumeCreate=new CountDownLatch(1);
        final AtomicBoolean paused=new AtomicBoolean();
        Barrier(Object source){this.source=source;}
        @Override public Object intercept(Invocation call)throws Throwable {
            String id=((MappedStatement)call.getArgs()[0]).getId(),thread=Thread.currentThread().getName();
            boolean buyer=id.endsWith("PaidLicenseStore.activeBuyer");
            if(buyer&&thread.equals("w18-review-body"))bodyReachesBuyer.countDown();
            Object result=call.proceed();
            if(id.endsWith("PaidProfessionalOperationStore.lock")&&thread.equals("w18-review-create")){
                Object tx=TransactionSynchronizationManager.getResource(source);assertNotNull(tx);
                if(preparing==null)preparing=tx;
                else if(tx!=preparing&&paused.compareAndSet(false,true)){
                    operationHeld.countDown();if(!resumeCreate.await(10,TimeUnit.SECONDS))throw new AssertionError("Create barrier timeout");
                }
            }
            return result;
        }
        @Override public Object plugin(Object target){return Plugin.wrap(target,this);}
        @Override public void setProperties(Properties properties){}
    }
}
