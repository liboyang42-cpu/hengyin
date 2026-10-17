package com.chengyinhub.business.service;
import com.chengyinhub.business.mapper.PersonalExplorationGoalMapper;
import com.chengyinhub.business.domain.PersonalExplorationGoal;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual production XML + migration. Dedicated VPS only; never run against an existing schema. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_TEN_FEATURES_MYSQL_IT", matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonalExplorationGoalMySqlIT {
    CloudTenMySqlFixture fixture; JdbcTemplate db; PersonalExplorationGoalMapper mapper; PersonalExplorationGoalService service;
    long clock = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli();
    @BeforeAll void open() throws Exception {
        fixture = CloudTenMySqlFixture.open("exploration_goals");
        DriverManagerDataSource source = new DriverManagerDataSource(fixture.config().databaseUrl(), fixture.config().user(), fixture.config().password());
        db = new JdbcTemplate(source);
        Path migration = Paths.get("sql/migration_20261012_six_exploration_goals.sql");
        if (!Files.exists(migration)) migration = Paths.get("chengyinhub-admin/sql/migration_20261012_six_exploration_goals.sql");
        try (Connection connection = fixture.connection()) { ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration.toFile())); }
        db.execute("create table topic_route_session(id bigint primary key,member_id bigint,topic_id bigint,status int,completed_at datetime(3),config_version bigint default 1) engine=InnoDB");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/PersonalExplorationGoalMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PersonalExplorationGoalMapper.class);
        service = new PersonalExplorationGoalService(mapper) { @Override protected long now() { return clock; } };
    }
    @BeforeEach void clear() { db.update("delete from topic_route_session"); db.update("delete from personal_exploration_goal"); clock = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli(); }
    @AfterAll void close() throws Exception { if (fixture != null) fixture.close(); }
    void fact(long id, long owner, Long route, int status, String completed) { db.update("insert into topic_route_session(id,member_id,topic_id,status,completed_at) values(?,?,?,?,?)", id, owner, route, status, completed); }
    long progress(String metric) { return mapper.progress(7L, metric, "2026-10-12 00:00:00", "2026-10-15 00:00:00", "2026-10-14 12:00:00.000"); }
    Map<String,Object> create(String request) { return service.create(7L, request, "DISTINCT_ROUTES", 2, "2026-10-12", "2026-10-15"); }
    @Test void actualMapperDeduplicatesRouteAcrossSessionsAndVersionsAndScopesOwner() {
        fact(1,7,10L,1,"2026-10-12 00:00:00"); fact(2,7,10L,1,"2026-10-13 12:00:00");
        fact(3,7,11L,1,"2026-10-14 11:00:00"); fact(4,8,12L,1,"2026-10-12 00:00:00");
        db.update("update topic_route_session set config_version=2 where id=2");
        assertEquals(2, progress("DISTINCT_ROUTES")); assertEquals(3, progress("EXPLORATION_DAYS"));
    }
    @Test void halfOpenDayWindowExcludesEndAndIncompleteMissingFutureFacts() {
        fact(1,7,10L,1,"2026-10-11 23:59:59.999"); fact(2,7,11L,1,"2026-10-12 00:00:00");
        fact(3,7,12L,1,"2026-10-15 00:00:00"); fact(4,7,13L,0,"2026-10-12 01:00:00");
        fact(5,7,14L,1,null); fact(6,7,null,1,"2026-10-13 01:00:00"); fact(7,7,15L,1,"2026-10-14 12:00:00.001");
        assertEquals(1, progress("DISTINCT_ROUTES")); assertEquals(1, progress("EXPLORATION_DAYS"));
    }
    @Test void daysAreNonconsecutiveAndMultipleSameDayCompletionsCountOnce() {
        fact(1,7,10L,1,"2026-10-12 00:00:00"); fact(2,7,11L,1,"2026-10-12 23:59:59.999"); fact(3,7,12L,1,"2026-10-14 00:00:00");
        assertEquals(2, progress("EXPLORATION_DAYS"));
    }
    @Test void privateHistoricalAggregateNeedsNoCurrentTopicTitleOrPermissionJoin() {
        // No cms_topic exists in this fixture: archived/unavailable routes retain only private aggregate evidence.
        fact(1,7,999L,1,"2026-10-12 01:00:00");
        Map<String,Object> out = create("historical_goal_0001"); assertEquals(1L, out.get("progress"));
        assertFalse(out.containsKey("title")); assertFalse(out.containsKey("routeIds")); assertFalse(out.containsKey("coordinates"));
    }
    @Test void cancellationIsIdempotentCannotBeRevivedAndCannotCrossOwner() {
        Map<String,Object> created = create("cancel_goal_00001"); Long id = Long.valueOf((String)created.get("id"));
        assertThrows(ServiceException.class, () -> service.cancel(8L,id)); assertEquals("CANCELLED",service.cancel(7L,id).get("state"));
        assertEquals("CANCELLED",service.cancel(7L,id).get("state")); assertEquals("CANCELLED",create("cancel_goal_00001").get("state"));
        assertEquals(1,db.queryForObject("select count(*) from personal_exploration_goal",Integer.class).intValue());
    }
    @Test void uniqueOriginalRequestSupportsConcurrentCreateAndRejectsChangedPayload() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Map<String,Object>> job = () -> { assertTrue(start.await(10,TimeUnit.SECONDS)); return create("concurrent_goal_001"); };
            Future<Map<String,Object>> a = pool.submit(job), b = pool.submit(job); start.countDown();
            assertEquals(a.get(15,TimeUnit.SECONDS).get("id"), b.get(15,TimeUnit.SECONDS).get("id"));
            assertEquals(1,db.queryForObject("select count(*) from personal_exploration_goal",Integer.class).intValue());
            assertThrows(ServiceException.class, () -> service.create(7L,"concurrent_goal_001","DISTINCT_ROUTES",3,"2026-10-12","2026-10-15"));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS)); }
    }
    @Test void binaryRequestIdentityAndLargeIdPageRemainExact() {
        create("case_Sensitive_001"); create("case_sensitive_001");
        db.update("update personal_exploration_goal set id=9007199254740999 where request_id='case_Sensitive_001'");
        Map<String,Object> result = service.list(7L,null,1); assertEquals("9007199254740999", result.get("nextBeforeId"));
        assertEquals(1, mapper.page(7L,9007199254740999L,20).size()); assertTrue(mapper.page(8L,null,20).isEmpty());
    }
    @Test void serverAsOfAndReadTimeFactsControlCommemoration() {
        Map<String,Object> created = create("current_fact_00001"); Long id = Long.valueOf((String)created.get("id"));
        fact(1,7,10L,1,"2026-10-12 01:00:00"); fact(2,7,11L,1,"2026-10-12 08:00:00.001");
        assertEquals("ACTIVE",service.detail(7L,id).get("state")); clock++;
        assertEquals("ACHIEVED",service.detail(7L,id).get("state")); db.update("update topic_route_session set status=0 where id=2");
        assertEquals("ACTIVE",service.detail(7L,id).get("state"));
    }
}
