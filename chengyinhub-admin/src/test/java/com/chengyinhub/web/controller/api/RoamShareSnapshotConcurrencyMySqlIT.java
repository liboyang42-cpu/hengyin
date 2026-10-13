package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.RoamShareSnapshotMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.RoamSharePrivacyService;
import com.chengyinhub.business.service.support.RoamShareSnapshotService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoamShareSnapshotConcurrencyMySqlIT {
    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private RoamShareSnapshotMapper snapshots;
    private UmsMemberMapper members;
    private DriverManagerDataSource source;

    @BeforeAll
    void setup() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("roam_share_cap");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertEquals("8.0.45", admin.queryForObject("select version()", String.class),
                "CYV-P2-002 必须在 Oracle MySQL 8.0.45 上验收");
        assertEquals("REPEATABLE-READ", admin.queryForObject("select @@transaction_isolation", String.class));
        database = "chengyin_roam_share_cap_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + database);
        source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        db.execute(new String(Files.readAllBytes(Paths.get("sql/migration_20260917_roam_share_snapshot.sql")),
                StandardCharsets.UTF_8));
        db.execute("create table ums_member(id bigint primary key) engine=InnoDB");
        db.update("insert into ums_member(id) values(9)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/RoamShareSnapshotMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        snapshots = session.getMapper(RoamShareSnapshotMapper.class);
        members = session.getMapper(UmsMemberMapper.class);
    }

    @BeforeEach
    void seed49() {
        db.update("delete from roam_share_snapshot");
        for (int i = 1; i <= 49; i++) {
            snapshots.upsertSnapshot(String.format("%032x", i), 9L, (long) i, "{\"ts\":3600000,\"track\":[],\"pois\":[]}");
        }
        db.update("update roam_share_snapshot set update_time=date_add('2026-01-01 00:00:00', interval client_ts second)");
    }

    @AfterAll
    void cleanup() {
        if (database != null && admin != null) {
            admin.execute("drop database " + database);
            assertEquals(0, admin.queryForObject(
                    "select count(*) from information_schema.schemata where schema_name=?", Integer.class, database));
        }
    }

    @Test
    void twoFirstPublishesFrom49NeverLeaveMoreThan50() throws Exception {
        CountDownLatch firstCountReached = new CountDownLatch(1);
        CountDownLatch releaseFirstCount = new CountDownLatch(1);
        CountDownLatch secondLockAttempted = new CountDownLatch(1);
        CountDownLatch secondCountReached = new CountDownLatch(1);
        AtomicInteger countCalls = new AtomicInteger();
        AtomicInteger lockCalls = new AtomicInteger();
        RoamShareSnapshotMapper synchronizedCount = (RoamShareSnapshotMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {RoamShareSnapshotMapper.class}, (proxy, method, args) -> {
                    Object value = method.invoke(snapshots, args);
                    if (method.getName().equals("countByMember")) {
                        if (countCalls.incrementAndGet() == 1) {
                            firstCountReached.countDown();
                            assertTrue(releaseFirstCount.await(5, TimeUnit.SECONDS), "首请求未获准继续");
                        } else {
                            secondCountReached.countDown();
                        }
                    }
                    return value;
                });
        UmsMemberMapper observedMembers = (UmsMemberMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {UmsMemberMapper.class}, (proxy, method, args) -> {
                    if (method.getName().equals("lockMemberById") && lockCalls.incrementAndGet() == 2) {
                        secondLockAttempted.countDown();
                    }
                    return method.invoke(members, args);
                });
        ApiRoamShareController first = controller(synchronizedCount, observedMembers);
        ApiRoamShareController second = controller(synchronizedCount, observedMembers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = workers.submit(() -> { await(start); assertEquals(200, first.publish(100L, "{}").get("code")); });
            start.countDown();
            assertTrue(firstCountReached.await(5, TimeUnit.SECONDS), "首请求未进入上限检查");
            Future<?> two = workers.submit(() -> assertEquals(200, second.publish(101L, "{}").get("code")));
            assertTrue(secondLockAttempted.await(5, TimeUnit.SECONDS), "第二请求未到达会员行锁");
            assertFalse(secondCountReached.await(300, TimeUnit.MILLISECONDS),
                    "首请求提交前第二请求已穿过会员行锁；移除锁时该负控必须变红");
            assertFalse(two.isDone(), "首请求提交前第二请求不得完成");
            releaseFirstCount.countDown();
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstCount.countDown();
            workers.shutdownNow();
        }
        assertEquals(50, snapshots.countByMember(9L), "49 条时两个并发首次发布不得留下 51 条");
        assertNull(snapshots.selectSnapshotJsonByToken(String.format("%032x", 1)), "必须只汰汰 update_time/id 最旧记录");
        assertNotNull(snapshots.selectSnapshotJsonByToken(String.format("%032x", 2)));
        assertNotNull(snapshots.selectTokenByMemberTs(9L, 100L));
        assertNotNull(snapshots.selectTokenByMemberTs(9L, 101L));
    }

    @Test
    void updatingExistingTokenAtTheCapDoesNotEvictAnotherSnapshot() {
        snapshots.upsertSnapshot(String.format("%032x", 50), 9L, 50L,
                "{\"ts\":3600000,\"track\":[],\"pois\":[]}");
        String existingToken = snapshots.selectTokenByMemberTs(9L, 25L);
        assertEquals(200, controller(snapshots).publish(25L, "{\"routeName\":\"已更新\"}").get("code"));
        assertEquals(50, snapshots.countByMember(9L));
        assertEquals(existingToken, snapshots.selectTokenByMemberTs(9L, 25L), "更新已有足迹必须复用令牌");
        assertNotNull(snapshots.selectTokenByMemberTs(9L, 1L), "更新已有 token 不得误删别条分享");
    }

    private ApiRoamShareController controller(RoamShareSnapshotMapper mapper) {
        return controller(mapper, members);
    }

    private ApiRoamShareController controller(RoamShareSnapshotMapper mapper, UmsMemberMapper memberMapper) {
        ApiRoamShareController controller = new ApiRoamShareController() {
            @Override Long uid() { return 9L; }
        };
        RoamShareSnapshotService target = new RoamShareSnapshotService(mapper, memberMapper);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(controller, "snapshotMapper", mapper);
        ReflectionTestUtils.setField(controller, "privacyService", new RoamSharePrivacyService());
        ReflectionTestUtils.setField(controller, "snapshotService", proxy.getProxy());
        return controller;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
