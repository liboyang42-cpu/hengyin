package com.chengyinhub.business.service.rendezvous;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.service.impl.ImServiceImpl;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Existing IM operation, original mapper SQL and accepted durable store in isolated H2. */
public class RendezvousImBlockMutationTest {
    RendezvousConsentPersistenceTest f;
    SqlSessionTemplate session;
    ImServiceImpl im;
    RendezvousImBlockMutation hook;
    volatile CountDownLatch guardAttempt;

    @BeforeEach void setup() throws Exception {
        f = new RendezvousConsentPersistenceTest(); f.setup();
        f.jdbc.execute("CREATE TABLE im_block(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT NOT NULL,blocked_member_id BIGINT NOT NULL,create_time TIMESTAMP,UNIQUE(member_id,blocked_member_id))");
        Configuration c = new Configuration(new Environment("isolated-block", new SpringManagedTransactionFactory(), f.dataSource));
        c.addInterceptor(new GuardProbe());
        c.getTypeAliasRegistry().registerAlias("ImBlock", ImBlock.class);
        for (String name : Arrays.asList("RendezvousConsentMapper.xml", "ImBlockMapper.xml")) {
            String path = "chengyinhub-system/src/main/resources/mapper/business/" + name;
            byte[] xml = Files.readAllBytes(RendezvousConsentPersistenceTest.sourceRoot().resolve(path));
            if (name.equals("ImBlockMapper.xml") && f.jdbc.queryForObject("SELECT H2VERSION()", String.class).startsWith("2.")) {
                // H2 2.x removed SYSDATE(): explicit test-only clock syntax adaptation.
                // H2 1.4 executes original XML unchanged; neither run is MySQL evidence.
                xml = new String(xml, java.nio.charset.StandardCharsets.UTF_8).replace("sysdate()", "CURRENT_TIMESTAMP")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            try (InputStream input = new java.io.ByteArrayInputStream(xml)) {
                new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));
        hook = new RendezvousImBlockMutation(MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION, session, f.manager);
        im = new ImServiceImpl();
        ReflectionTestUtils.setField(im, "blockMapper", session.getMapper(ImBlockMapper.class));
        ReflectionTestUtils.setField(im, "imBlockMutation", hook);
    }
    @AfterEach void cleanup() { f.cleanup(); }
    void stopped(View[] pair) {
        assertEquals(0, f.count("w16_match_reservation"));
        // Read raw committed state before any lazy service read could clean it up.
        assertEquals("SAFETY_STOPPED", f.jdbc.queryForObject("SELECT state FROM w16_rendezvous_match", String.class));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM w16_match_ticket WHERE active_actor_id IN (1,2)", Integer.class));
        assertNull(f.service.read(1, pair[0].ticketId).publicPlace);
        assertNull(f.service.read(2, pair[1].ticketId).publicPlace);
    }
    @Test void actualExistingBlockImmediatelyStopsPairInEitherDirection() {
        View[] pair = f.pair(); im.block(2L, 1L); stopped(pair);
        assertTrue(im.isBlockedBetween(1L, 2L));
    }
    @Test void repeatedBlockAndUnblockNeverRestoreMutualConsent() {
        View[] p=f.pair(); f.service.accept(1,p[0].ticketId,1); f.service.accept(2,p[1].ticketId,2);
        im.block(1L,2L); stopped(p); long revision=f.service.read(1,p[0].ticketId).version;
        im.block(1L,2L); assertEquals(revision,f.service.read(1,p[0].ticketId).version);
        assertEquals(1,f.count("im_block")); im.unblock(1L,2L); stopped(p);
        assertFalse(im.isBlockedBetween(1L,2L));
    }
    @Test void unrelatedMatchAndWaitingTicketStayUnchanged() {
        View[] p=f.pair(); View third=f.join(3); im.block(1L,3L);
        assertEquals(2,f.count("w16_match_reservation"));
        assertEquals(MatchState.PROPOSED,f.service.read(1,p[0].ticketId).matchState);
        assertEquals(TicketState.WAITING,f.service.read(3,third.ticketId).ticketState);
    }
    @Test void absentAdapterPreservesOriginalBlockBehaviorAndSql() {
        ReflectionTestUtils.setField(im,"imBlockMutation",null); View[] p=f.pair();
        im.block(1L,2L); im.block(1L,2L);
        assertEquals(1,f.count("im_block")); assertEquals(2,f.count("w16_match_reservation"));
        assertThrows(RuntimeException.class,()->im.block(1L,1L));
        assertThrows(RuntimeException.class,()->im.block(null,2L));
        assertEquals(MatchState.PROPOSED,f.service.read(1,p[0].ticketId).matchState);
    }
    @Test void failureAfterInsertAndTicketCleanupRollsBackBlockAndBothReservations() {
        View[] p=f.pair();
        f.jdbc.execute("ALTER TABLE w16_rendezvous_match ADD CONSTRAINT reject_safety CHECK(state <> 'SAFETY_STOPPED')");
        RuntimeException rejected=assertThrows(RuntimeException.class,()->im.block(1L,2L));
        assertTrue(rejected.getMessage().contains("REJECT_SAFETY"));
        assertEquals(0,f.count("im_block")); assertEquals(2,f.count("w16_match_reservation"));
        assertEquals(TicketState.RESERVED,f.service.read(1,p[0].ticketId).ticketState);
        assertEquals(TicketState.RESERVED,f.service.read(2,p[1].ticketId).ticketState);
    }
    @Test void rejectedOriginalInsertCannotTerminateConsent() {
        View[] p=f.pair(); f.jdbc.execute("ALTER TABLE im_block ADD CONSTRAINT reject_block CHECK(member_id <> 1)");
        RuntimeException rejected=assertThrows(RuntimeException.class,()->im.block(1L,2L));
        assertTrue(rejected.getMessage().contains("REJECT_BLOCK"));
        assertEquals(0,f.count("im_block")); assertEquals(2,f.count("w16_match_reservation"));
        assertEquals(MatchState.PROPOSED,f.service.read(1,p[0].ticketId).matchState);
    }
    @Test void mismatchedTransactionResourceIsRejectedBeforeAnyMutation() {
        org.h2.jdbcx.JdbcDataSource other=new org.h2.jdbcx.JdbcDataSource();
        other.setURL("jdbc:h2:mem:unrelated_block_resource");
        assertThrows(IllegalArgumentException.class,()->new RendezvousImBlockMutation(
                MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION,session,new DataSourceTransactionManager(other)));
        assertEquals(0,f.count("im_block"));
    }
    @Test void disabledModeAndNestedTransactionCannotPersistBlock() {
        ReflectionTestUtils.setField(im,"imBlockMutation",new RendezvousImBlockMutation(
                MyBatisRendezvousConsentStore.Mode.DISABLED,session,f.manager));
        assertThrows(IllegalStateException.class,()->im.block(1L,2L));
        ReflectionTestUtils.setField(im,"imBlockMutation",hook);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(f.manager).execute(s->{im.block(1L,2L);return null;}));
        assertEquals(0,f.count("im_block"));
    }
    @Test void blockAndAcceptRaceCannotLeaveConsentActive() throws Exception {
        View[] p=f.pair(); ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Future<?> block=workers.submit(()->{await(start);im.block(1L,2L);});
            Future<?> accept=workers.submit(()->{await(start);f.service.accept(2,p[1].ticketId,1);});
            start.countDown(); block.get(10,TimeUnit.SECONDS);accept.get(10,TimeUnit.SECONDS); stopped(p);
        } finally {workers.shutdownNow();}
    }
    @Test void guardPreventsBlockPersistenceUntilPriorConsentTransactionCompletes() throws Exception {
        View[] p=f.pair(); ExecutorService workers=Executors.newSingleThreadExecutor();
        guardAttempt=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Future<?>> result=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            f.store.transaction(tx->{
                result.set(workers.submit(()->im.block(1L,2L)));await(guardAttempt);
                assertThrows(TimeoutException.class,()->result.get().get(150,TimeUnit.MILLISECONDS));
                assertEquals(0,f.count("im_block"));return null;
            });
            result.get().get(10,TimeUnit.SECONDS);stopped(p);
        } finally {workers.shutdownNow();}
    }
    @Test void mutableBlockInputCannotChangeCapturedPairWhileWaitingForGuard() throws Exception {
        View[] p=f.pair(); f.join(3); ExecutorService workers=Executors.newSingleThreadExecutor();
        guardAttempt=new CountDownLatch(1); ImBlock request=new ImBlock();request.setMemberId(1L);request.setBlockedMemberId(2L);
        java.util.concurrent.atomic.AtomicReference<Future<?>> result=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            f.store.transaction(tx->{
                result.set(workers.submit(()->hook.insert(request)));await(guardAttempt);
                request.setBlockedMemberId(3L); return null;
            });
            result.get().get(10,TimeUnit.SECONDS); stopped(p);
            assertTrue(im.isBlockedBetween(1L,2L));assertFalse(im.isBlockedBetween(1L,3L));
        } finally {workers.shutdownNow();}
    }
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(
            type=org.apache.ibatis.executor.Executor.class,method="query",args={org.apache.ibatis.mapping.MappedStatement.class,
            Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class}))
    class GuardProbe implements org.apache.ibatis.plugin.Interceptor {
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            if (((org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0]).getId().endsWith(".lockGuard")
                    && guardAttempt != null) guardAttempt.countDown();
            return invocation.proceed();
        }
    }
    static void await(CountDownLatch latch) {try {assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}
}
