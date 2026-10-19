package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/** Oracle MySQL 8.0.45 only. Uses existing loopback-only unique scratch and verified DROP.
 * No H2 inheritance, fallback, credentials or business database name is accepted here.
 */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class RendezvousConsentMySqlIT {
    private static final String MIGRATION = "w16_consent_persistence_candidate";
    private static final String MIGRATION_SHA = "216099864b936d7a1fbc1e3a190a39b0a477651b8f78f9ed5a92ae41645e2936";
    private final IsolatedMySqlSchema mysql = new IsolatedMySqlSchema();
    private DataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private MyBatisRendezvousConsentStore store;
    private RendezvousConsentService service;
    private final TestClock clock = new TestClock();
    private final TestPolicy policy = new TestPolicy();
    private String schema, commit, run;

    @BeforeEach void setup() throws Exception {
        commit = System.getProperty("w16.test.commit", ""); run = System.getProperty("w16.test.run", "");
        assertTrue(commit.matches("[0-9a-f]{40}"), "Exact checkout commit binding required");
        assertTrue(run.matches("[0-9a-f]{32}"), "Fresh invocation binding required");
        byte[] bytes = Files.readAllBytes(root().resolve("chengyinhub-admin/sql/migration_" + MIGRATION + ".sql"));
        assertEquals(MIGRATION_SHA, hex(MessageDigest.getInstance("SHA-256").digest(bytes)), "Only accepted original DDL bytes");
        source = mysql.open("w16_consent");
        try (Connection connection = source.getConnection()) { schema = connection.getCatalog(); }
        mysql.apply(MIGRATION);
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        store = newStore(); service = new RendezvousConsentService(store, policy, clock);
    }
    @AfterEach void cleanup(TestInfo info) throws Exception {
        mysql.close(); // prints verified DROP only after information_schema confirms absence
        if (schema != null) System.out.println("W16_MYSQL_EVIDENCE METHOD=" + info.getTestMethod().get().getName()
                + " VERSION=8.0.45 SCHEMA=" + schema + " MIGRATION_SHA256=" + MIGRATION_SHA
                + " COMMIT=" + commit + " RUN=" + run + " SCRATCH_DROPPED=true");
    }
    private MyBatisRendezvousConsentStore newStore() throws Exception {
        Configuration config = new Configuration(new Environment("w16-real-mysql", new SpringManagedTransactionFactory(), source));
        String resource = "mapper/business/RendezvousConsentMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "Current reactor mapper XML required");
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        return new MyBatisRendezvousConsentStore(MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION,
                new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)), manager);
    }
    private View join(long actor) { return service.join(actor, "synthetic-pool", "synthetic-policy-v1", 1000); }
    private View[] pair() {
        View a = join(1), b = join(2); service.propose(a.ticketId, b.ticketId, "synthetic-public-poi");
        return new View[]{service.read(1, a.ticketId), service.read(2, b.ticketId)};
    }
    private long reservations() { return jdbc.queryForObject("SELECT COUNT(*) FROM w16_match_reservation", Long.class); }
    @Test void originalDdlCreatesInnoDbAndGeneratedActorUniqueness() {
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'w16\\_%' AND engine='InnoDB'", Integer.class));
        assertEquals("STORED GENERATED", jdbc.queryForObject("SELECT extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='w16_match_ticket' AND column_name='active_actor_id'", String.class));
        assertEquals("utf8mb4_bin", jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='w16_match_ticket' AND column_name='id'", String.class));
        join(7);
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO w16_match_ticket(id,actor_id,pool,eligibility_version,expires_at,state) VALUES('duplicate',7,'synthetic-pool','synthetic-policy-v1',20000,'WAITING')"));
    }
    @Test void bilateralReceiptsPersistAcrossIndependentMapperSessions() throws Exception {
        View[] p = pair(); service.accept(1, p[0].ticketId, 1);
        RendezvousConsentService fresh = new RendezvousConsentService(newStore(), policy, clock);
        View b = fresh.read(2, p[1].ticketId); assertTrue(b.acceptedByOther); assertFalse(b.acceptedByMe);
        assertEquals(MatchState.MUTUAL_ACCEPTED, fresh.accept(2, b.ticketId, b.version).matchState);
        assertEquals(2, reservations());
    }
    @Test void concurrentOptInCommitsOneActiveTicket() throws Exception {
        RendezvousConsentService other = new RendezvousConsentService(newStore(), policy, clock);
        List<String> ids = race(() -> join(7).ticketId, () -> other.join(7,"synthetic-pool","synthetic-policy-v1",1000).ticketId);
        assertEquals(ids.get(0), ids.get(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM w16_match_ticket WHERE active_actor_id=7", Integer.class));
    }
    @Test void concurrentProposalsCannotReserveOneTicketTwice() throws Exception {
        View a = join(1), b = join(2), c = join(3);
        RendezvousConsentService other = new RendezvousConsentService(newStore(), policy, clock);
        List<Boolean> outcomes = race(() -> propose(service,a,b), () -> propose(other,c,a));
        assertNotEquals(outcomes.get(0), outcomes.get(1)); assertEquals(2, reservations());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM w16_rendezvous_match", Integer.class));
    }
    @Test void partialReservationFailureRollsBackBothTickets() {
        View a = join(1), b = join(2);
        assertThrows(IllegalStateException.class, () -> store.transaction(tx -> {
            tx.save(tx.ticket(a.ticketId).transition(TicketState.RESERVED,"synthetic-match"));
            tx.save(tx.ticket(b.ticketId).transition(TicketState.RESERVED,"synthetic-match"));
            throw new IllegalStateException("synthetic interruption before proposal write");
        }));
        assertEquals(0, reservations()); assertEquals(TicketState.WAITING, service.read(1,a.ticketId).ticketState);
        assertEquals(TicketState.WAITING, service.read(2,b.ticketId).ticketState);
    }
    @Test void receiptOverwriteRejectionRollsBackAcceptanceMutation() {
        View[] p=pair(); service.accept(1,p[0].ticketId,1);
        assertThrows(IllegalStateException.class, () -> store.transaction(tx -> {
            Ticket b=tx.ticket(p[1].ticketId); tx.save(b.transition(TicketState.ACCEPTED,b.matchId)); Match m=tx.match(b.matchId);
            tx.save(new Match(m.id,m.firstTicket,m.secondTicket,m.publicPlace,m.expiresAt,m.version+1,123L,123L,MatchState.MUTUAL_ACCEPTED)); return null;
        }));
        View b=service.read(2,p[1].ticketId); assertFalse(b.acceptedByMe); assertEquals(TicketState.RESERVED,b.ticketState); assertEquals(2,b.version);
    }
    @Test void concurrentCancelAndAcceptCannotResurrectProposal() throws Exception {
        View[] p=pair(); RendezvousConsentService other=new RendezvousConsentService(newStore(),policy,clock);
        race(() -> service.cancel(1,p[0].ticketId), () -> other.accept(2,p[1].ticketId,1));
        assertEquals(MatchState.CANCELLED,service.read(1,p[0].ticketId).matchState); assertEquals(0,reservations());
    }
    @Test void expiryCommitsSlotReleaseAtExactBoundary() {
        View[] p=pair(); clock.now+=1000;
        assertEquals(MatchState.EXPIRED,service.accept(1,p[0].ticketId,1).matchState);
        assertEquals(0,reservations()); assertNotEquals(p[0].ticketId,join(1).ticketId);
    }
    @Test void rejectedRejoinStillCommitsSafetyTermination() {
        View[] p=pair(); policy.eligible=false;
        assertThrows(Rejection.class,()->join(1)); assertEquals(0,reservations());
        assertEquals(MatchState.SAFETY_STOPPED,service.read(2,p[1].ticketId).matchState);
    }
    @Test void duplicateAcceptanceKeepsOriginalReceiptAndRevision() {
        View[] p=pair(); View accepted=service.accept(1,p[0].ticketId,1); clock.now+=50;
        View retry=service.accept(1,p[0].ticketId,1); assertEquals(accepted.version,retry.version);
        assertEquals(Long.valueOf(10000),jdbc.queryForObject("SELECT first_accepted_at FROM w16_rendezvous_match WHERE id=?",Long.class,accepted.matchId));
    }
    @Test void globalGuardHoldsAcrossIndependentMysqlConnections() throws Exception {
        MyBatisRendezvousConsentStore other=newStore(); ExecutorService pool=Executors.newSingleThreadExecutor();
        CountDownLatch started=new CountDownLatch(1),entered=new CountDownLatch(1);
        try (Connection connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement sql=connection.createStatement()) { sql.executeQuery("SELECT id FROM w16_consent_guard WHERE id=1 FOR UPDATE"); }
            Future<?> pending=pool.submit(()->{started.countDown();other.transaction(tx->{entered.countDown();return null;});});
            assertTrue(started.await(2,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); boolean observedWait=false;
            while(System.nanoTime()<deadline) {
                Long waiting=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='w16_consent_guard'",Long.class);
                if(waiting!=null && waiting>0) {observedWait=true;break;} Thread.sleep(20);
            }
            assertTrue(observedWait,"MySQL must report the actual guard lock wait"); assertEquals(1,entered.getCount());
            connection.commit(); pending.get(5,TimeUnit.SECONDS); assertEquals(0,entered.getCount());
        } finally {pool.shutdownNow();}
    }
    @Test void cancelledActorsCanOptInAgainWithoutReusingReservations() {
        View[] p=pair();service.cancel(2,p[1].ticketId);View a=join(1),b=join(2);
        assertNotEquals(p[0].ticketId,a.ticketId);assertNotEquals(p[1].ticketId,b.ticketId);
        service.propose(a.ticketId,b.ticketId,"synthetic-public-poi");assertEquals(2,reservations());
        assertEquals(MatchState.CANCELLED,service.read(1,p[0].ticketId).matchState);
    }
    private boolean propose(RendezvousConsentService s,View a,View b) {try {s.propose(a.ticketId,b.ticketId,"synthetic-public-poi");return true;}catch(Rejection e){return false;}}
    private <T> List<T> race(Callable<T>a,Callable<T>b)throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {Future<T>x=pool.submit(()->{start.await();return a.call();});Future<T>y=pool.submit(()->{start.await();return b.call();});start.countDown();return Arrays.asList(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS));}
        finally {pool.shutdownNow();}
    }
    private static Path root() {Path p=Paths.get(System.getProperty("user.dir")).toAbsolutePath();return "chengyinhub-admin".equals(p.getFileName().toString())?p.getParent():p;}
    private static String hex(byte[] bytes) {StringBuilder b=new StringBuilder();for(byte value:bytes)b.append(String.format("%02x",value&255));return b.toString();}
    private static final class TestClock extends Clock {
        long now=10000; public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    private static final class TestPolicy implements Safety {
        boolean eligible=true;
        public boolean eligible(long actor,String pool,String version,long now){return eligible;}
        public boolean blocked(long a,long b){return false;} public boolean compatible(long a,long b,String pool,long now){return true;}
        public boolean publicMeetingAllowed(String pool,String place,long now){return "synthetic-public-poi".equals(place);}
    }
}
