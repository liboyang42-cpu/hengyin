package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.GameSessionRuntimeServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Real Spring transaction and independent MySQL writer; mapper SQL is a minimal probe.
 * This proves aggregate read isolation, not production mapper-schema coverage. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OWN_C06_10Test {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("c06_snapshot");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private boolean created;
    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
    }
    @BeforeAll void setup() {
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_HOST"));
        String suffix = System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX");
        assertNotNull(suffix); assertFalse(suffix.trim().isEmpty());
        admin().execute("CREATE DATABASE " + mysql.databaseName()); created = true;
        DriverManagerDataSource source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE snapshot_probe(id BIGINT PRIMARY KEY, revision INT, team_id BIGINT) ENGINE=InnoDB");
    }
    @AfterAll void cleanup() { if (created) admin().execute("DROP DATABASE " + mysql.databaseName()); }
    @BeforeEach void seed() {
        jdbc.update("DELETE FROM snapshot_probe");
        jdbc.update("INSERT INTO snapshot_probe VALUES(10,1,40)");
    }
    @Test void proxiedViewKeepsOldRevisionAndTeamThenNextRequestSeesNewState() throws Exception {
        interleave(true, 40L);
    }
    @Test void negativeControlWithoutProxyMixesOldRevisionAndNewTeam() throws Exception {
        interleave(false, 41L);
    }
    private void interleave(boolean proxied, long expectedTeam) throws Exception {
        GameSessionRuntimeServiceImpl target = new GameSessionRuntimeServiceImpl();
        GameSessionRuntimeMapper runtime = mock(GameSessionRuntimeMapper.class);
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        PlayTeamMapper teams = mock(PlayTeamMapper.class);
        ReflectionTestUtils.setField(target, "runtimeMapper", runtime);
        ReflectionTestUtils.setField(target, "activityMapper", activities);
        ReflectionTestUtils.setField(target, "playTeamMapper", teams);
        ReflectionTestUtils.setField(target, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock) () -> new Date());
        CmsActivity activity = new CmsActivity(); activity.setId(10L); activity.setTopicId(20L); activity.setStatus(1L);
        when(activities.selectCmsActivityById(10L)).thenReturn(activity);
        when(teams.hasPaidRegistration(7L, 2, 10L)).thenReturn(1);
        CountDownLatch read = new CountDownLatch(1), resume = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean pause = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(runtime.selectSessionByActivity(10L)).thenAnswer(call -> {
            GameSession session = new GameSession(); session.setId(30L); session.setActivityId(10L);
            session.setStatus(GameSession.RUNNING); session.setModuleConfigSnapshotJson("{\"playMode\":\"ROUTE\"}");
            session.setRevision(jdbc.queryForObject("SELECT revision FROM snapshot_probe WHERE id=10", Integer.class));
            if (pause.compareAndSet(true, false)) {
                assertEquals(proxied, TransactionSynchronizationManager.isActualTransactionActive());
                read.countDown(); assertTrue(resume.await(10, TimeUnit.SECONDS));
            }
            return session;
        });
        when(runtime.selectActiveTeamId(10L, 7L)).thenAnswer(call ->
                jdbc.queryForObject("SELECT team_id FROM snapshot_probe WHERE id=10", Long.class));
        GameSessionRuntimeServiceImpl reader = target;
        if (proxied) {
            ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
            factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            reader = (GameSessionRuntimeServiceImpl) factory.getProxy();
        }
        final GameSessionRuntimeServiceImpl endpoint = reader;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<GameSessionView> future = worker.submit(() -> endpoint.view(GameActorContext.player(7L), 10L));
        try {
            assertTrue(read.await(10, TimeUnit.SECONDS));
            new TransactionTemplate(manager).executeWithoutResult(status ->
                    jdbc.update("UPDATE snapshot_probe SET revision=2,team_id=41 WHERE id=10"));
            resume.countDown();
            GameSessionView view = future.get(10, TimeUnit.SECONDS);
            assertEquals(Integer.valueOf(1), view.getRevision());
            assertEquals(Long.valueOf(expectedTeam), view.getPlayer().get("teamId"));
            GameSessionView next = endpoint.view(GameActorContext.player(7L), 10L);
            assertEquals(Integer.valueOf(2), next.getRevision());
            assertEquals(Long.valueOf(41), next.getPlayer().get("teamId"));
        } finally {
            resume.countDown(); future.cancel(true); worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
