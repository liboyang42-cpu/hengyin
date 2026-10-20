package com.chengyinhub.business.discovery;

import static com.chengyinhub.business.discovery.EggDiscoveryEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual mapper and Spring transactions. H2 is not MySQL or production authority evidence. */
class EggDiscoveryPersistenceH2Test {
    protected JdbcTemplate jdbc;
    private DataSource dataSource;
    private DataSourceTransactionManager manager;
    private EggDiscoveryTransactionalService service;
    private EggDiscoveryMapper mapper;
    private final Instant now=Instant.parse("2026-10-04T02:00:00.123456789Z");
    private final Scope theme=Scope.theme("topic","release","run");
    private Rule rule;
    private volatile Snapshot facts;
    private final AtomicInteger sourceReads=new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        dataSource=createDataSource(); jdbc=new JdbcTemplate(dataSource); manager=new DataSourceTransactionManager(dataSource);
        applySchema(jdbc);
        rule=new Rule(theme,"egg",1,Arrays.asList("a","b"),now.minusSeconds(60),now.plusSeconds(60),Duration.ofSeconds(30),"Asia/Shanghai");
        facts=snapshot(Arrays.asList(event("secret-a",1,"a",-20),event("secret-b",2,"b",-10)));
        openService(); service.openEvaluation(7,rule);
    }
    protected DataSource createDataSource() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:egg_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        return ds;
    }
    protected void applySchema(JdbcTemplate jdbc) throws Exception {
        Path migration=Paths.get("chengyinhub-admin/sql/migration_20261004_egg_discovery_evaluation.sql");
        if(!Files.isRegularFile(migration)) migration=Paths.get("sql/migration_20261004_egg_discovery_evaluation.sql");
        assertTrue(Files.isRegularFile(migration),"Actual source migration required");
        String sql=new String(Files.readAllBytes(migration),StandardCharsets.UTF_8)
                .replaceAll("(?m)^--[^\\n]*","").replace(" CHARACTER SET ascii COLLATE ascii_bin","")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        for(String part:sql.split(";")) {
            String statement=part.trim();
            if(!statement.isEmpty() && !statement.startsWith("SELECT (")) jdbc.execute(statement);
        }
    }
    protected void dropFailureCheck(String table,String constraint) { jdbc.execute("ALTER TABLE "+table+" DROP CONSTRAINT "+constraint); }
    private void openService() throws Exception {
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource("mapper/discovery/EggDiscoveryMapper.xml"));
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(EggDiscoveryMapper.class);
        service=new EggDiscoveryTransactionalService(EggDiscoveryTransactionalService.Mode.ISOLATED_PERSISTENCE_EVALUATION,
                mapper,manager,(m,s,d,n)->{sourceReads.incrementAndGet();return facts;},Clock.fixed(now,ZoneOffset.UTC));
    }
    private Event event(String id,long seq,String step,long seconds) { return new Event(id,7,theme,seq,step,now.plusSeconds(seconds),true); }
    private Snapshot snapshot(List<Event> events) { return new Snapshot(7,rule,now,true,true,true,true,true,events); }
    private EggDiscoveryTransactionalService.EvaluationReceipt discover() { return service.discover(7,theme,"egg"); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    private void blocked(String code,Runnable action) { assertEquals(code,assertThrows(IllegalStateException.class,action::run).getMessage()); }

    @Test void defaultsDisabledAndNestedTransactionsRejected() {
        blocked("DISCOVERY_PERSISTENCE_DISABLED",()->new EggDiscoveryTransactionalService().discover(7,theme,"egg"));
        new TransactionTemplate(manager).execute(status->{blocked("NESTED_TRANSACTION_FORBIDDEN",()->discover()); return null;});
        assertEquals(0,count("w14_egg_evaluation_receipt"));
    }
    @Test void repeatedSetupAndProcessRestartReplayOneReceiptAndOneOutbox() throws Exception {
        service.openEvaluation(7,rule);
        EggDiscoveryTransactionalService.EvaluationReceipt one=discover();
        assertFalse(one.replay); assertEquals("ISOLATED_EVALUATION",one.evidenceLevel);
        openService();
        EggDiscoveryTransactionalService.EvaluationReceipt replay=discover();
        assertTrue(replay.replay); assertEquals(one.publicView(),replay.publicView());
        assertEquals(now.minusSeconds(10),replay.discoveredAt);
        assertEquals(1,count("w14_egg_evaluation_scope")); assertEquals(1,count("w14_egg_evaluation_receipt"));
        assertEquals(1,count("w14_egg_evaluation_outbox")); assertEquals(2,sourceReads.get());
    }
    @Test void pendingDiscoveryHasNoReceiptOrEvent() {
        facts=snapshot(Collections.singletonList(event("a",1,"a",-20)));
        assertNull(discover()); assertEquals(0,count("w14_egg_evaluation_receipt")); assertEquals(0,count("w14_egg_evaluation_outbox"));
    }
    @Test void receiptAndOutboxAreAtomicOnOutboxFailure() {
        jdbc.execute("ALTER TABLE w14_egg_evaluation_outbox ADD CONSTRAINT injected CHECK(event_type<>'EVALUATED_STORY_DISCOVERY')");
        assertThrows(RuntimeException.class,()->discover());
        assertEquals(0,count("w14_egg_evaluation_receipt")); assertEquals(0,count("w14_egg_evaluation_outbox"));
        dropFailureCheck("w14_egg_evaluation_outbox","injected"); assertNotNull(discover());
        assertEquals(1,count("w14_egg_evaluation_receipt"));
    }
    @Test void receiptFailureNeverEmitsAnEvent() {
        jdbc.execute("ALTER TABLE w14_egg_evaluation_receipt ADD CONSTRAINT injected CHECK(member_id<>7)");
        assertThrows(RuntimeException.class,()->discover());
        assertEquals(0,count("w14_egg_evaluation_receipt")); assertEquals(0,count("w14_egg_evaluation_outbox"));
    }
    @Test void currentExternalRevocationBlocksReplayAndKeepsHistoricalReceipt() {
        discover(); facts=new Snapshot(7,rule,now,false,true,true,true,true,facts.events);
        blocked("DISCOVERY_UNAVAILABLE",()->discover());
        assertEquals(1,count("w14_egg_evaluation_receipt")); assertEquals(1,count("w14_egg_evaluation_outbox"));
    }
    @Test void localRevocationIsAtomicIdempotentAndNeverDeletesReceipt() throws Exception {
        discover(); service.revokeEvaluation(7,theme,"egg"); service.revokeEvaluation(7,theme,"egg");
        openService(); blocked("EVALUATION_REVOKED",()->discover());
        blocked("EVALUATION_REVOKED",()->service.openEvaluation(7,rule));
        assertEquals(1,count("w14_egg_evaluation_receipt")); assertEquals(2,count("w14_egg_evaluation_outbox"));
        assertEquals(2,jdbc.queryForObject("SELECT revision FROM w14_egg_evaluation_scope",Long.class));
    }
    @Test void failedRevocationOutboxRollsBackStatusAndRetrySucceeds() {
        discover(); jdbc.execute("ALTER TABLE w14_egg_evaluation_outbox ADD CONSTRAINT injected CHECK(event_type<>'EVALUATION_REVOKED')");
        assertThrows(RuntimeException.class,()->service.revokeEvaluation(7,theme,"egg"));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM w14_egg_evaluation_scope",String.class));
        assertTrue(discover().replay);
        dropFailureCheck("w14_egg_evaluation_outbox","injected"); service.revokeEvaluation(7,theme,"egg");
        blocked("EVALUATION_REVOKED",()->discover());
    }
    @Test void concurrentDuplicateDiscoverySerializesAtOwnerScope() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<EggDiscoveryTransactionalService.EvaluationReceipt> task=()->{start.await();return discover();};
            Future<EggDiscoveryTransactionalService.EvaluationReceipt> first=pool.submit(task),second=pool.submit(task);
            start.countDown(); EggDiscoveryTransactionalService.EvaluationReceipt a=first.get(10,TimeUnit.SECONDS),b=second.get(10,TimeUnit.SECONDS);
            assertEquals(a.receiptId,b.receiptId); assertNotEquals(a.replay,b.replay);
            assertEquals(1,count("w14_egg_evaluation_receipt")); assertEquals(1,count("w14_egg_evaluation_outbox"));
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentRevocationWaitsForDiscoveryThenBlocksAllReplays() throws Exception {
        CountDownLatch sourceEntered=new CountDownLatch(1),releaseSource=new CountDownLatch(1),revocationStarted=new CountDownLatch(1);
        EggDiscoveryTransactionalService blockedSource=new EggDiscoveryTransactionalService(
                EggDiscoveryTransactionalService.Mode.ISOLATED_PERSISTENCE_EVALUATION,mapper,manager,(m,s,d,n)->{
                    sourceEntered.countDown();
                    try { assertTrue(releaseSource.await(5,TimeUnit.SECONDS)); }
                    catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("INTERRUPTED"); }
                    return facts;
                },Clock.fixed(now,ZoneOffset.UTC));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> discovery=pool.submit(()->blockedSource.discover(7,theme,"egg"));
            assertTrue(sourceEntered.await(5,TimeUnit.SECONDS));
            Future<?> revocation=pool.submit(()->{revocationStarted.countDown();service.revokeEvaluation(7,theme,"egg");});
            assertTrue(revocationStarted.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->revocation.get(100,TimeUnit.MILLISECONDS));
            releaseSource.countDown(); discovery.get(10,TimeUnit.SECONDS); revocation.get(10,TimeUnit.SECONDS);
            blocked("EVALUATION_REVOKED",()->discover());
            assertEquals(1,count("w14_egg_evaluation_receipt")); assertEquals(2,count("w14_egg_evaluation_outbox"));
        } finally {releaseSource.countDown();pool.shutdownNow();}
    }
    @Test void concurrentFirstSetupIsOneImmutableBinding() throws Exception {
        Rule extra=new Rule(theme,"another-egg",1,rule.steps,rule.startsAt,rule.endsAt,rule.maxDuration,rule.publicationZone);
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Void> task=()->{start.await();service.openEvaluation(7,extra);return null;};
            Future<Void> first=pool.submit(task),second=pool.submit(task); start.countDown();
            first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
            assertEquals(2,count("w14_egg_evaluation_scope")); assertEquals(0,count("w14_egg_evaluation_receipt"));
        } finally {pool.shutdownNow();}
    }
    @Test void ownerScopeAndReleaseMismatchRejectedBeforeSourceRead() {
        blocked("EVALUATION_SCOPE_MISMATCH",()->service.discover(8,theme,"egg"));
        blocked("EVALUATION_SCOPE_MISMATCH",()->service.discover(7,Scope.theme("topic","different","run"),"egg"));
        blocked("EVALUATION_SCOPE_MISMATCH",()->service.revokeEvaluation(8,theme,"egg"));
        assertEquals(0,sourceReads.get()); assertEquals(0,count("w14_egg_evaluation_receipt"));
    }
    @Test void immutableDefinitionRejectsSameVersionMutationAndVersionUpgrade() {
        Rule changed=new Rule(theme,"egg",1,Arrays.asList("x"),rule.startsAt,rule.endsAt,rule.maxDuration,rule.publicationZone);
        blocked("IMMUTABLE_RULE_CONFLICT",()->service.openEvaluation(7,changed));
        facts=new Snapshot(7,changed,now,true,true,true,true,true,facts.events);
        blocked("IMMUTABLE_RULE_CONFLICT",()->discover());
        Rule upgraded=new Rule(theme,"egg",2,rule.steps,rule.startsAt,rule.endsAt,rule.maxDuration,rule.publicationZone);
        blocked("IMMUTABLE_RULE_CONFLICT",()->service.openEvaluation(7,upgraded));
        assertEquals(0,count("w14_egg_evaluation_receipt"));
    }
    @Test void replayAllowsAppendButRejectsDeletionOrMutationOfCapturedEvidence() {
        List<Event> original=new ArrayList<Event>(facts.events); original.add(event("unmatched",3,"x",-5)); facts=snapshot(original); discover();
        List<Event> appended=new ArrayList<Event>(original); appended.add(event("late",4,"x",-4)); facts=snapshot(appended); assertTrue(discover().replay);
        facts=snapshot(original.subList(0,2)); blocked("SOURCE_HISTORY_CHANGED",()->discover());
        List<Event> mutated=new ArrayList<Event>(original.subList(0,2)); mutated.add(event("unmatched",3,"other",-5));
        facts=snapshot(mutated); blocked("SOURCE_HISTORY_CHANGED",()->discover());
        assertEquals(1,count("w14_egg_evaluation_receipt")); assertEquals(1,count("w14_egg_evaluation_outbox"));
    }
    @Test void databaseCannotPromoteEvaluationEvidenceAndClientViewHasNoSourceIdentifiers() {
        EggDiscoveryTransactionalService.EvaluationReceipt result=discover();
        for(String table:Arrays.asList("w14_egg_evaluation_scope","w14_egg_evaluation_receipt","w14_egg_evaluation_outbox"))
            assertThrows(RuntimeException.class,()->jdbc.update("UPDATE "+table+" SET evidence_level='AUTHORITATIVE'"));
        assertFalse(result.publicView().toString().contains("secret-a"));
        assertFalse(jdbc.queryForObject("SELECT history_hashes FROM w14_egg_evaluation_receipt",String.class).contains("secret"));
        assertEquals(new HashSet<String>(Arrays.asList("receiptId","kind","discoveredAt","evidenceLevel")),result.publicView().keySet());
    }
    @Test void mapAndThemeBindingsRemainSeparateInStorage() {
        String themeReceipt=discover().receiptId;
        Scope map=Scope.map("topic","release","run");
        Rule mapRule=new Rule(map,"egg",1,rule.steps,rule.startsAt,rule.endsAt,rule.maxDuration,rule.publicationZone);
        List<Event> events=Arrays.asList(new Event("secret-a",7,map,1,"a",now.minusSeconds(20),true),new Event("secret-b",7,map,2,"b",now.minusSeconds(10),true));
        facts=new Snapshot(7,mapRule,now,true,true,true,true,true,events); service.openEvaluation(7,mapRule);
        assertNotEquals(themeReceipt,service.discover(7,map,"egg").receiptId);
        assertEquals(2,count("w14_egg_evaluation_scope")); assertEquals(2,count("w14_egg_evaluation_receipt"));
    }
}
