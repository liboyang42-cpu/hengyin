package com.chengyinhub.business.city;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.chengyinhub.business.mapper.UmsMemberMapper;

import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.city.CityContracts.*;
import static com.chengyinhub.business.city.CityRuntimeState.*;
import static com.chengyinhub.business.city.CityRuntimeTransactionalService.*;

/** Actual mapper XML + Spring DB transactions, NOT MySQL evidence or production authority. */
class CityRuntimePersistenceH2Test {
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00.123456789Z");
    // Synthetic isolated-test allocation. This number is not a proposed production registry allocation.
    private static final CityLedgerMapping TEST_MAPPING = new CityLedgerMapping("CITY_VISIT_SETTLEMENT", 210000001, "test-only-ledger-review");
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private CityRuntimeMapper store;
    private CityPointsMutationMapper points;
    private UmsMemberMapper ordinary;
    private CityRuntimeTransactionalService service;
    private final MutableClock clock = new MutableClock();
    private Rules rules;
    private Board board;
    private Participation team;
    private Property property;
    private RoundGrant grant;
    private Offer offer;
    private PayerConsent consent;

    @BeforeEach void setup() throws Exception {
        dataSource = createDataSource(); jdbc = new JdbcTemplate(dataSource); manager = new DataSourceTransactionManager(dataSource);
        applySchema(jdbc);
        openService(TEST_MAPPING);
        jdbc.update("INSERT INTO ums_member VALUES (11,10,1,0),(22,40,1,0),(33,0,1,0)");
        clock.now = NOW;
        rules = new Rules("rules", repeat('a',64), "policy", 100, 100, 3, 30);
        board = new Board("board", rules, BoardStatus.OPEN, 1, NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        team = new Participation("board", "visitors", 1, ids(11L,22L), true);
        property = new Property("board", "shop", "operators", 1, NOW.minusSeconds(60), true);
        grant = new RoundGrant("board", "visitors", "rules", 1, NOW.minusSeconds(10), NOW.plusSeconds(600), "dice-4");
        offer = offer("offer", "visit-scope", "reward-scope", 12, amounts(11,7,22,3), NOW.plusSeconds(500));
        consent = consent(offer, NOW.plusSeconds(400));
        service.createBoard(board); service.createBudget("board", "budget", "test-only-budget-approval", 1, 20);
    }
    protected DataSource createDataSource() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:city_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        return source;
    }
    protected void applySchema(JdbcTemplate jdbc) throws Exception {
        jdbc.execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, point INT, status INT, del_flag INT)");
        jdbc.execute("CREATE TABLE ums_member_points_detail (id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, current_points DECIMAL(10,0), after_points DECIMAL(10,0), change_points DECIMAL(10,0), change_reason VARCHAR(500), event_type INT, event_id BIGINT, reward_business_key VARCHAR(128), change_type INT, del_flag INT, create_time TIMESTAMP, UNIQUE(member_id,event_type,event_id), UNIQUE(member_id,reward_business_key))");
        Path migration = Paths.get("sql/migration_20261002_city_visit_persistence.sql");
        if (!Files.isRegularFile(migration)) migration = Paths.get("chengyinhub-admin/sql/migration_20261002_city_visit_persistence.sql");
        assertTrue(Files.isRegularFile(migration), "actual repository migration must exist");
        String sql = new String(Files.readAllBytes(migration), StandardCharsets.UTF_8)
                .replaceAll("(?m)^--[^\\n]*", "")
                .replaceAll(" CHARACTER SET ascii COLLATE ascii_bin", "")
                .replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("SELECT (") && trimmed.contains("information_schema")) continue;
            jdbc.execute(trimmed);
        }
    }
    protected void dropFailureCheck(String table, String name) { jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + name); }
    private void openService(CityLedgerMapping mapping) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/city/CityRuntimeMapper.xml"),
                new ClassPathResource("mapper/city/CityPointsMutationMapper.xml"), new ClassPathResource("mapper/business/UmsMemberMapper.xml"));
        SqlSessionFactory sessions = factory.getObject(); assertNotNull(sessions);
        SqlSessionTemplate template = new SqlSessionTemplate(sessions);
        store = template.getMapper(CityRuntimeMapper.class); points = template.getMapper(CityPointsMutationMapper.class);
        ordinary = template.getMapper(UmsMemberMapper.class);
        service = new CityRuntimeTransactionalService(Mode.ISOLATED_PERSISTENCE_EVALUATION, store, points, manager, clock, mapping);
    }
    private static String repeat(char c, int count) { char[] chars = new char[count]; Arrays.fill(chars,c); return new String(chars); }
    private static TreeSet<Long> ids(Long... values) { return new TreeSet<Long>(Arrays.asList(values)); }
    private static Map<Long,Long> amounts(long... values) {
        Map<Long,Long> result = new LinkedHashMap<Long,Long>();
        for (int i=0;i<values.length;i+=2) result.put(values[i],values[i+1]); return result;
    }
    private Offer offer(String offerId, String scope, String rewardScope, long fee, Map<Long,Long> rewards, Instant expiry) {
        Participation operators = new Participation("board", "operators", 7, ids(33L), true);
        FeeAllocation allocation = fee == 0 ? null : new FeeAllocation("test-only-fee-approval", "policy", operators, amounts(33,fee));
        return new Offer(offerId, "board", "shop", "operators", 1, "visitors", 1, "service", scope, "task", rewardScope,
                rules, "availability", 11, fee, rewards, allocation, expiry);
    }
    private PayerConsent consent(Offer value, Instant expiry) { return new PayerConsent("consent", 11, value.fingerprint, value.fee, expiry); }
    private TaskProof proof(String proofId, Offer value) {
        return new TaskProof(proofId, "board", "visitors", "shop", "task", value.visitKey(), 11, 1,
                clock.instant(), NOW.plusSeconds(600), true, true);
    }
    private Result roll() { return service.roll("board",team,11,1,0,"roll",grant); }
    private Result accept() { return service.acceptVisit("board",team,11,1,"accept",offer,property,"availability",grant,consent,"budget",1); }
    private Result complete(String request) { return service.completeVisit("board",team,11,1,2,request,offer.visitKey(),proof("proof",offer),property); }
    private Result cancel(String request) { return service.cancelVisit("board",team,11,1,2,request,offer.visitKey()); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private long value(String column, String table) { return jdbc.queryForObject("SELECT " + column + " FROM " + table, Long.class); }
    private long balance(long id) { return jdbc.queryForObject("SELECT point FROM ums_member WHERE id=?",Long.class,id); }
    private void acceptedUnsettled() {
        assertEquals(10,balance(11)); assertEquals(40,balance(22)); assertEquals(0,balance(33));
        assertEquals(0,count("ums_member_points_detail")); assertEquals(0,count("city_point_effect_member"));
        assertEquals(10,value("reserved","city_reward_budget")); assertEquals(0,value("consumed","city_reward_budget"));
        assertEquals(2,count("city_action_effect")); assertEquals(2,count("city_domain_outbox"));
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT status FROM city_service_visit",String.class));
    }
    @Test void defaultsDisabledAndMissingLedgerAllocationFailClosed() throws Exception {
        assertThrows(CityRuleException.class, () -> new CityRuntimeTransactionalService().readState("board"));
        roll(); accept(); openService(null);
        assertEquals("CITY_LEDGER_MAPPING_REQUIRED", assertThrows(CityRuleException.class, () -> complete("complete")).getMessage());
        acceptedUnsettled();
        assertThrows(CityRuleException.class, () -> new CityLedgerMapping("CITY_VISIT_SETTLEMENT",15,"x"));
        assertThrows(CityRuleException.class, () -> new CityLedgerMapping("OTHER",210000001,"x"));
    }
    @Test void completeConservesFeeAndGrossBudgetAndReloadsExactSnapshot() throws Exception {
        roll(); accept(); Result result = complete("complete");
        assertTrue(result.committed); assertFalse(result.replay);
        assertEquals(5,balance(11)); assertEquals(43,balance(22)); assertEquals(12,balance(33));
        assertEquals(3,count("ums_member_points_detail")); assertEquals(3,count("city_point_effect_member"));
        assertEquals(10,value("consumed","city_reward_budget")); assertEquals(0,value("reserved","city_reward_budget"));
        assertEquals(12,value("sum(fee_credit)","city_point_effect_member"));
        assertEquals(12,value("sum(fee_debit)","city_point_effect_member"));
        assertEquals(10,value("sum(net_change)","city_point_effect_member"));
        assertEquals(3,value("count(DISTINCT sequence)","city_domain_outbox"));
        openService(TEST_MAPPING);
        CityRuntimeState state = service.readState("board");
        assertEquals(offer.fingerprint,state.visits.get(offer.visitKey()).offer.fingerprint);
        assertEquals(NOW,state.visits.get(offer.visitKey()).acceptedAt);
        assertEquals(VisitStatus.SUCCEEDED,state.visits.get(offer.visitKey()).status);
        assertEquals(result.effectId, complete("after-restart").effectId);
        assertEquals(3,count("ums_member_points_detail")); assertEquals(4,count("city_action_receipt"));
    }
    @Test void changedDiceAndNewRequestReplayDurableRoundWithoutGrant() throws Exception {
        Result first = roll();
        openService(TEST_MAPPING);
        RoundGrant changed = new RoundGrant("board","visitors","rules",1,grant.opensAt,grant.closesAt,"dice-6");
        Result replay = service.roll("board",team,22,1,999,"different-request",changed);
        assertTrue(replay.replay); assertEquals(first.effectId,replay.effectId); assertEquals("dice-4",replay.receipt.outcomeId);
        assertEquals(3,service.readState("board").rounds.get(grant.businessKey()).remainingActions);
        assertEquals(1,count("city_action_round")); assertEquals(2,count("city_action_receipt"));
        RoundGrant next = new RoundGrant("board","visitors","rules",2,grant.opensAt,grant.closesAt,"dice-2");
        assertThrows(CityRuleException.class, () -> service.roll("board",team,22,1,1,"different-request",next));
    }
    @Test void sameRequestCannotChangeOfferAndBusinessAliasRemainsBound() {
        roll(); accept();
        assertTrue(service.acceptVisit("board",team,22,9,"accept-alias",offer,property,"availability",grant,consent,"budget",1).replay);
        Offer different = offer("other","visit-scope","reward-scope",11,amounts(11,7,22,3),offer.expiresAt);
        assertThrows(CityRuleException.class, () -> service.acceptVisit("board",team,22,2,"accept-alias",different,property,"availability",grant,consent(different,consent.expiresAt),"budget",1));
        assertEquals(10,value("reserved","city_reward_budget")); assertEquals(2,service.readState("board").rounds.get(grant.businessKey()).remainingActions);
    }
    private Object[] race(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch go=new CountDownLatch(1);
        try {
            Future<?> a=pool.submit(() -> { go.await(); try{return first.call();}catch(Exception e){return e;} });
            Future<?> b=pool.submit(() -> { go.await(); try{return second.call();}catch(Exception e){return e;} });
            go.countDown(); return new Object[]{a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)};
        } finally { pool.shutdownNow(); }
    }
    @Test void simultaneousAcceptReservesAndConsumesActionOnce() throws Exception {
        roll();
        Object[] results=race(() -> accept(), () -> service.acceptVisit("board",team,22,1,"accept-2",offer,property,"availability",grant,consent,"budget",1));
        assertTrue(results[0] instanceof Result, String.valueOf(results[0])); assertTrue(results[1] instanceof Result,String.valueOf(results[1]));
        assertEquals(1,count("city_service_visit")); assertEquals(1,count("city_reward_reservation"));
        assertEquals(10,value("reserved","city_reward_budget")); assertEquals(2,service.readState("board").rounds.get(grant.businessKey()).remainingActions);
    }
    @Test void simultaneousCompleteChargesOnceAndAddsRequestAliases() throws Exception {
        roll(); accept(); Object[] results=race(() -> complete("done-1"), () -> complete("done-2"));
        assertTrue(results[0] instanceof Result,String.valueOf(results[0])); assertTrue(results[1] instanceof Result,String.valueOf(results[1]));
        assertEquals(((Result)results[0]).effectId,((Result)results[1]).effectId);
        assertEquals(3,count("ums_member_points_detail")); assertEquals(10,value("consumed","city_reward_budget"));
    }
    @Test void simultaneousCompleteCancelHasExactlyOneTerminalWinner() throws Exception {
        roll(); accept(); Object[] results=race(() -> complete("done"), () -> cancel("cancel"));
        assertEquals(1,Arrays.stream(results).filter(x -> x instanceof Result).count());
        assertEquals(1,Arrays.stream(results).filter(x -> x instanceof CityRuleException).count());
        assertEquals(3,count("city_action_effect")); assertEquals(3,count("city_domain_outbox"));
        assertEquals(0,value("reserved","city_reward_budget"));
        assertEquals(10,value("consumed+released","city_reward_budget"));
        String status=jdbc.queryForObject("SELECT status FROM city_service_visit",String.class);
        if ("SUCCEEDED".equals(status)) assertEquals(3,count("ums_member_points_detail"));
        else {assertEquals("CANCELLED",status); assertEquals(0,count("ums_member_points_detail"));}
    }
    @Test void simultaneousRollPreservesOneOutcome() throws Exception {
        Object[] results=race(() -> roll(), () -> service.roll("board",team,22,1,0,"roll-2",new RoundGrant("board","visitors","rules",1,grant.opensAt,grant.closesAt,"dice-6")));
        assertTrue(results[0] instanceof Result,String.valueOf(results[0])); assertTrue(results[1] instanceof Result,String.valueOf(results[1]));
        assertEquals(((Result)results[0]).receipt.outcomeId,((Result)results[1]).receipt.outcomeId);
        assertEquals(1,count("city_action_round")); assertEquals(1,count("city_action_effect"));
    }
    @Test void rollbackAfterActualPointWritesRestoresEveryTableAndRetryCommitsOnce() throws Exception {
        roll(); accept();
        jdbc.execute("ALTER TABLE city_point_effect_member ADD CONSTRAINT fail_operator_fact CHECK (member_id <> 33)");
        assertThrows(RuntimeException.class, () -> complete("done")); acceptedUnsettled();
        dropFailureCheck("city_point_effect_member", "fail_operator_fact");
        openService(TEST_MAPPING); assertFalse(complete("done").replay); assertEquals(3,count("ums_member_points_detail"));
    }
    @Test void rollbackAtOutboxRestoresBudgetPointsProofAndTerminalStatus() {
        roll(); accept();
        jdbc.execute("ALTER TABLE city_domain_outbox ADD CONSTRAINT fail_success_outbox CHECK (event_type <> 'VISIT_SUCCEEDED')");
        assertThrows(RuntimeException.class, () -> complete("done")); acceptedUnsettled(); assertEquals(0,count("city_consumed_scope"));
    }
    @Test void rollbackAtReceiptRestoresAcceptReservationRoundAndEffect() {
        roll();
        jdbc.execute("ALTER TABLE city_action_receipt ADD CONSTRAINT fail_accept_receipt CHECK (request_id <> 'accept')");
        assertThrows(RuntimeException.class, () -> accept());
        assertEquals(0,count("city_service_visit")); assertEquals(0,count("city_reward_reservation"));
        assertEquals(0,value("reserved","city_reward_budget")); assertEquals(1,count("city_action_effect"));
        assertEquals(3,service.readState("board").rounds.get(grant.businessKey()).remainingActions);
    }
    @Test void expiredConsentCannotAcceptOrChargeButCanExitFree() {
        roll(); clock.now=consent.expiresAt;
        assertThrows(CityRuleException.class, () -> accept()); assertEquals(0,count("city_service_visit"));
        clock.now=NOW; accept(); clock.now=consent.expiresAt;
        assertThrows(CityRuleException.class, () -> complete("done")); acceptedUnsettled();
        cancel("free-exit"); assertEquals(10,value("released","city_reward_budget")); assertEquals(10,balance(11));
    }
    @Test void changedMembershipCannotCompleteButCurrentMemberCanCancel() {
        roll(); accept(); Participation changed=new Participation("board","visitors",2,ids(11L,22L),true);
        assertThrows(CityRuleException.class, () -> service.completeVisit("board",changed,11,2,2,"done",offer.visitKey(),proof("proof",offer),property));
        acceptedUnsettled(); service.cancelVisit("board",changed,11,2,2,"cancel",offer.visitKey());
        assertEquals(10,value("released","city_reward_budget"));
    }
    @Test void freezeAndExpiryBlockNewChargeButDoNotDestroyOriginalFreeExit() {
        roll(); accept(); service.refreshBoard(new Board("board",rules,BoardStatus.FROZEN,2,board.opensAt,board.closesAt));
        assertThrows(CityRuleException.class, () -> complete("done")); clock.now=offer.expiresAt;
        Result result=cancel("cancel"); assertEquals("VISIT_EXPIRED",result.receipt.kind);
        assertEquals(10,value("released","city_reward_budget")); assertEquals(0,count("ums_member_points_detail"));
    }
    @Test void closedPropertyBlocksCompletionAndOriginalOwnerAllocationSurvivesTransfer() {
        roll(); accept(); Property closed=new Property("board","shop","new-owner",2,NOW,false);
        assertThrows(CityRuleException.class, () -> service.completeVisit("board",team,11,1,2,"done",offer.visitKey(),proof("proof",offer),closed));
        Property transferred=new Property("board","shop","new-owner",3,NOW,true);
        service.completeVisit("board",team,11,1,2,"done",offer.visitKey(),proof("proof",offer),transferred);
        assertEquals(12,balance(33));
    }
    @Test void zeroRewardCannotAttachAnyBudgetAndPersistsNoReservation() {
        roll(); offer=offer("zero","zero-scope","zero-reward",2,Collections.<Long,Long>emptyMap(),NOW.plusSeconds(500));
        consent=consent(offer,NOW.plusSeconds(400));
        assertThrows(CityRuleException.class, () -> accept()); assertEquals(0,count("city_reward_reservation"));
        service.acceptVisit("board",team,11,1,"accept",offer,property,"availability",grant,consent,null,0);
        complete("done"); assertEquals(8,balance(11)); assertEquals(2,balance(33));
        assertEquals(0,count("city_reward_reservation")); assertEquals(0,value("consumed","city_reward_budget"));
    }
    @Test void grossBudgetCannotBeFundedByFeeAndInsufficientBudgetRollsBackAcceptance() {
        roll(); jdbc.update("UPDATE city_reward_budget SET ceiling=9");
        assertThrows(CityRuleException.class, () -> accept()); assertEquals(0,count("city_service_visit"));
        assertEquals(0,value("reserved","city_reward_budget")); assertEquals(3,service.readState("board").rounds.get(grant.businessKey()).remainingActions);
    }
    @Test void payerInsufficiencyCannotSpendRichTeammatesPoints() {
        roll(); accept(); jdbc.update("UPDATE ums_member SET point=0 WHERE id=11");
        assertThrows(CityRuleException.class, () -> complete("done")); assertEquals(0,balance(11)); assertEquals(40,balance(22));
        assertEquals(0,count("ums_member_points_detail")); assertEquals(10,value("reserved","city_reward_budget"));
    }
    @Test void signedIntOverflowRollsBackAllMemberWrites() {
        roll(); accept(); jdbc.update("UPDATE ums_member SET point=2147483647 WHERE id=33");
        assertThrows(CityRuleException.class, () -> complete("done"));
        assertEquals(10,balance(11)); assertEquals(40,balance(22)); assertEquals(2147483647L,balance(33));
        assertEquals(0,count("ums_member_points_detail")); assertEquals(0,value("consumed","city_reward_budget"));
    }
    @Test void netZeroStillConsumesGrossBudgetAndKeepsFactWithoutFakeDetail() {
        roll(); offer=offer("zero-net","zero-net-scope","reward",7,amounts(11,7),NOW.plusSeconds(500));
        consent=consent(offer,NOW.plusSeconds(400)); accept(); complete("done");
        assertEquals(10,balance(11)); assertEquals(7,balance(33)); assertEquals(1,count("ums_member_points_detail"));
        assertEquals(2,count("city_point_effect_member")); assertEquals(7,value("consumed","city_reward_budget"));
        assertNull(jdbc.queryForObject("SELECT points_detail_id FROM city_point_effect_member WHERE member_id=11",Long.class));
    }
    @Test void consumedRewardScopeCannotBeResetByNewVisitOrServiceRestart() throws Exception {
        roll(); accept(); complete("done"); openService(TEST_MAPPING);
        Offer second=offer("offer-2","scope-2","reward-scope",1,amounts(11,7,22,3),NOW.plusSeconds(500));
        service.acceptVisit("board",team,11,3,"accept-2",second,property,"availability",grant,consent(second,NOW.plusSeconds(400)),"budget",1);
        assertThrows(CityRuleException.class, () -> service.completeVisit("board",team,11,1,4,"done-2",second.visitKey(),proof("proof-2",second),property));
        assertEquals(3,count("ums_member_points_detail")); assertEquals(10,value("consumed","city_reward_budget"));
        service.cancelVisit("board",team,11,1,4,"cancel-2",second.visitKey()); assertEquals(10,value("released","city_reward_budget"));
    }
    @Test void rejectsNestedTransactionsInsteadOfSuspendingCrossDomainLocks() {
        new TransactionTemplate(manager).execute(status -> {
            ordinary.addPoints(11L,1);
            assertEquals("CITY_NESTED_TRANSACTION_FORBIDDEN",assertThrows(CityRuleException.class, () -> roll()).getMessage());
            return null;
        });
        assertEquals(0,count("city_action_effect")); assertEquals(11,balance(11));
    }
    @Test void legacyConditionalDeductionCommitsBeforeCityLockAndCurrentBalanceIsUsed() throws Exception {
        roll(); accept(); ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch legacyWrote=new CountDownLatch(1), release=new CountDownLatch(1), cityStarted=new CountDownLatch(1);
        try {
            Future<?> legacy=pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                assertEquals(1,ordinary.deductPoints(11L,4)); legacyWrote.countDown(); await(release); return null;
            }));
            assertTrue(legacyWrote.await(5,TimeUnit.SECONDS));
            Future<Result> city=pool.submit(() -> {cityStarted.countDown(); return complete("done");});
            assertTrue(cityStarted.await(5,TimeUnit.SECONDS)); assertThrows(TimeoutException.class, () -> city.get(150,TimeUnit.MILLISECONDS));
            release.countDown(); legacy.get(10,TimeUnit.SECONDS); city.get(10,TimeUnit.SECONDS);
            assertEquals(1,balance(11));
            assertEquals(6,jdbc.queryForObject("SELECT current_points FROM ums_member_points_detail WHERE member_id=11",Long.class));
        } finally {release.countDown(); pool.shutdownNow();}
    }
    @Test void currentLockReadDoesNotReuseEarlierMybatisOrOrdinarySnapshot() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch read=new CountDownLatch(1), wrote=new CountDownLatch(1), release=new CountDownLatch(1);
        try {
            Future<Long> waiting=pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                assertEquals(10,jdbc.queryForObject("SELECT point FROM ums_member WHERE id=11",Long.class));
                read.countDown(); await(wrote);
                return points.lockCurrentPoints(11).point;
            }));
            assertTrue(read.await(5,TimeUnit.SECONDS));
            Future<?> writer=pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                assertEquals(1,ordinary.addPoints(11L,6)); wrote.countDown(); await(release); return null;
            }));
            assertTrue(wrote.await(5,TimeUnit.SECONDS)); assertThrows(TimeoutException.class, () -> waiting.get(150,TimeUnit.MILLISECONDS));
            release.countDown(); writer.get(10,TimeUnit.SECONDS); assertEquals(16,waiting.get(10,TimeUnit.SECONDS));
        } finally {release.countDown(); pool.shutdownNow();}
    }
    @Test void mixedLegacyReturnAndCityCompletionHaveNoLostUpdate() throws Exception {
        roll(); accept(); Object[] results=race(() -> complete("done"), () -> new TransactionTemplate(manager).execute(status -> ordinary.addPoints(11L,9)));
        assertTrue(results[0] instanceof Result,String.valueOf(results[0])); assertEquals(1,results[1]);
        assertEquals(14,balance(11)); assertEquals(43,balance(22)); assertEquals(12,balance(33));
    }
    @Test void caseSensitiveKeysAndPerMemberEventUniquenessAreRealDatabaseConstraints() {
        service.createBoard(new Board("Board",rules,BoardStatus.OPEN,1,board.opensAt,board.closesAt)); assertEquals(2,count("city_board"));
        roll(); accept(); Result result=complete("done");
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO ums_member_points_detail(member_id,event_type,event_id) VALUES(11,?,?)",TEST_MAPPING.settlementEventType,result.effectId));
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO city_action_receipt SELECT * FROM city_action_receipt WHERE request_id='done'"));
    }
    @Test void concurrentBudgetBoundaryAllowsOnlyOneDistinctVisit() throws Exception {
        roll(); jdbc.update("UPDATE city_reward_budget SET ceiling=10");
        Offer other=offer("other","other-scope","other-reward",1,amounts(11,7,22,3),NOW.plusSeconds(500));
        Object[] results=race(() -> accept(), () -> service.acceptVisit("board",team,22,1,"accept-other",other,property,"availability",grant,consent(other,NOW.plusSeconds(400)),"budget",1));
        assertEquals(1,Arrays.stream(results).filter(x -> x instanceof Result).count());
        assertEquals(1,count("city_service_visit")); assertEquals(10,value("reserved","city_reward_budget"));
        assertEquals(2,service.readState("board").rounds.get(grant.businessKey()).remainingActions);
        Offer loser=results[0] instanceof Result ? other : offer;
        assertThrows(CityRuleException.class, () -> service.acceptVisit("board",team,11,2,"retry-loser",loser,property,"availability",grant,consent(loser,NOW.plusSeconds(400)),"budget",1));
        assertEquals(1,count("city_service_visit"));
    }
    @Test void cancelAndReleaseReplayAfterRestartCannotCompleteOrRegrant() throws Exception {
        roll(); accept(); Result cancelled=cancel("cancel"); openService(TEST_MAPPING);
        Result retry=cancel("cancel-again"); assertTrue(retry.replay); assertEquals(cancelled.effectId,retry.effectId);
        assertEquals(10,value("released","city_reward_budget")); assertEquals(0,value("reserved","city_reward_budget"));
        assertThrows(CityRuleException.class, () -> complete("late-complete"));
        assertTrue(service.acceptVisit("board",team,11,3,"rejoin-accept",offer,property,"availability",grant,consent,"budget",1).replay);
        assertEquals(VisitStatus.CANCELLED,service.readState("board").visits.get(offer.visitKey()).status);
        assertEquals(2,service.readState("board").rounds.get(grant.businessKey()).remainingActions);
        assertEquals(0,count("ums_member_points_detail"));
    }
    @Test void invalidLocationTaskScopeAndFutureProofLeaveAcceptedReceiptUntouched() {
        roll(); accept();
        TaskProof[] invalid={
            new TaskProof("bad-location","board","visitors","shop","task",offer.visitKey(),11,1,NOW,NOW.plusSeconds(600),false,true),
            new TaskProof("bad-task","board","visitors","shop","other-task",offer.visitKey(),11,1,NOW,NOW.plusSeconds(600),true,true),
            new TaskProof("bad-board","other-board","visitors","shop","task",offer.visitKey(),11,1,NOW,NOW.plusSeconds(600),true,true),
            new TaskProof("future","board","visitors","shop","task",offer.visitKey(),11,1,NOW.plusSeconds(1),NOW.plusSeconds(600),true,true)};
        for(TaskProof invalidProof:invalid) assertThrows(CityRuleException.class, () -> service.completeVisit("board",team,11,1,2,"bad",offer.visitKey(),invalidProof,property));
        acceptedUnsettled(); assertEquals(0,count("city_consumed_scope"));
    }
    @Test void zeroRewardPersistedWithUnexpectedReservationFailsBeforePointWrite() {
        roll(); offer=offer("zero","zero-scope","zero-reward",2,Collections.<Long,Long>emptyMap(),NOW.plusSeconds(500));
        consent=consent(offer,NOW.plusSeconds(400));
        service.acceptVisit("board",team,11,1,"accept",offer,property,"availability",grant,consent,null,0);
        jdbc.update("INSERT INTO city_reward_reservation VALUES(?,?,?,?,?,?,?)",repeat('b',64),"board","budget",offer.settlementKey(),offer.fingerprint,0,"RESERVED");
        assertThrows(CityRuleException.class, () -> complete("done"));
        assertThrows(CityRuleException.class, () -> cancel("cancel"));
        assertEquals(0,count("ums_member_points_detail")); assertEquals(0,value("reserved","city_reward_budget"));
    }
    @Test void existingLedgerUniqueFailureRollsBackEarlierPointMutation() {
        roll(); accept();
        jdbc.update("INSERT INTO ums_member_points_detail(member_id,reward_business_key) VALUES(11,?)","city:settlement:"+offer.settlementKey());
        assertThrows(RuntimeException.class, () -> complete("done"));
        assertEquals(10,balance(11)); assertEquals(40,balance(22)); assertEquals(0,balance(33));
        assertEquals(1,count("ums_member_points_detail")); assertEquals(0,count("city_point_effect_member"));
        assertEquals(10,value("reserved","city_reward_budget")); assertEquals(2,count("city_action_effect"));
    }
    private static void await(CountDownLatch latch) {
        try {if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("latch timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    private static final class MutableClock extends Clock {
        volatile Instant now=NOW;
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
