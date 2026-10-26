package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.OrientSessionMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IOrientScoringService;
import java.io.InputStream;
import java.util.Date;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL only. No assumptions/skip/fallback; invoked on the approved CI/VPS test target. */
class OrientRouteSettlementMySqlIT {
    private static PooledDataSource ds;
    private static TopicRouteSessionMapper routes;
    private static OrientSessionMapper clocks;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private IOrientScoringService scoring;
    private OrientRouteSettlement service;

    @BeforeAll static void setupDatabase() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host());
        ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", MybatisItDatabase.url("w804_route_settlement"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table topic_route_session(id bigint primary key,activity_id bigint,topic_id bigint,member_id bigint,play_instance_id varchar(64),config_version bigint,config_snapshot_json longtext,state_json longtext,status int,phase varchar(32),current_node_id bigint,recommended_node_id bigint,seed bigint,version int,completed_at datetime(3),create_time datetime(3),update_time datetime(3)) engine=InnoDB");
        jdbc.execute("create table orient_session_clock(session_id bigint primary key,member_id bigint,started_at datetime(3),ended_at datetime(3),elapsed_ms bigint,create_time datetime(3),update_time datetime(3)) engine=InnoDB");
        Configuration config = new Configuration(); config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String name : new String[]{"TopicRouteSessionMapper", "OrientSessionMapper"}) {
            String resource = "mapper/business/" + name + ".xml";
            try (InputStream in = OrientRouteSettlementMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        routes = sql.getMapper(TopicRouteSessionMapper.class); clocks = sql.getMapper(OrientSessionMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @BeforeEach void seed() {
        jdbc.update("delete from orient_session_clock"); jdbc.update("delete from topic_route_session");
        jdbc.update("insert into topic_route_session(id,activity_id,topic_id,member_id,play_instance_id,config_version,config_snapshot_json,state_json,status,version) values(51,0,17,7,'run-original',4,'{}','{}',0,0)");
        jdbc.update("insert into orient_session_clock(session_id,member_id,started_at) values(51,7,?)", new Date(1000));
        scoring = mock(IOrientScoringService.class); service = new OrientRouteSettlement(scoring, routes, clocks);
    }
    @AfterAll static void close() { if (ds != null) ds.forceCloseAll(); }
    private TopicRouteSession identity() { TopicRouteSession row = new TopicRouteSession(); row.setId(51L); row.setMemberId(7L); return row; }

    @Test void failedSettlementRollsBackRealTerminalCasAndClockTogether() {
        when(scoring.settleBySystem(51L, "lifecycle:settle:51")).thenThrow(new IllegalStateException("ORIENT_REVIEW_PENDING"));
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            TopicRouteSession row = service.lock(identity()); row.setStatus(TopicRouteSession.STATUS_COMPLETED); row.setCompletedAt(new Date(5000));
            assertEquals(1, routes.updateStateCas(row)); service.complete(row, new TopicRouteEngine.State());
        }));
        assertEquals(0, jdbc.queryForObject("select status from topic_route_session where id=51", Integer.class));
        assertEquals(0, jdbc.queryForObject("select version from topic_route_session where id=51", Integer.class));
        assertNull(clocks.selectClock(51L, 7L).getEndedAt());
    }

    @Test void realSessionLockSerializesTheNextWriterBeforeItCanTouchTaskFacts() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        try {
            Future<?> first = workers.submit(() -> tx.executeWithoutResult(status -> {
                service.lock(identity()); held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test lock timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(held.await(5, TimeUnit.SECONDS));
            Future<?> second = workers.submit(() -> tx.executeWithoutResult(status -> {
                secondStarted.countDown(); service.lock(identity());
            }));
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            verifyNoInteractions(scoring);
        } finally { release.countDown(); workers.shutdownNow(); }
    }
}
