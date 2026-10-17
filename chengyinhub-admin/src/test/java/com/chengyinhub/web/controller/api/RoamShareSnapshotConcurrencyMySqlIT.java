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
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoamShareSnapshotConcurrencyMySqlIT {
    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private RoamShareSnapshotMapper snapshots;
    private UmsMemberMapper members;
    private com.chengyinhub.business.mapper.RoamMapper roam;
    private DriverManagerDataSource source;
    private com.chengyinhub.business.mapper.ConsentRecordMapper consents;

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
        db.execute("alter table roam_share_snapshot add session_id bigint null");
        db.execute("create table roam_session(id bigint primary key, member_id bigint, status int, finished_at datetime) engine=InnoDB");
        db.update("insert into roam_session values(12,9,1,now())");
        db.execute("create table ums_member(id bigint primary key) engine=InnoDB");
        db.update("insert into ums_member(id) values(9)");
        db.execute("create table consent_record(id bigint auto_increment primary key,member_id bigint not null,doc_type varchar(32) not null,doc_version varchar(32) not null,scene varchar(32) not null,scope_type varchar(16),scope_id bigint,event_type varchar(16) not null,occurred_at datetime not null,request_id varchar(64) not null unique,ip_address varchar(64),create_time datetime not null) engine=InnoDB");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ConsentRecordMapper.xml"),new ClassPathResource("mapper/business/RoamShareSnapshotMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberMapper.xml"), new ClassPathResource("mapper/business/RoamMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        consents=session.getMapper(com.chengyinhub.business.mapper.ConsentRecordMapper.class);
        snapshots = session.getMapper(RoamShareSnapshotMapper.class);
        members = session.getMapper(UmsMemberMapper.class);
        roam = session.getMapper(com.chengyinhub.business.mapper.RoamMapper.class);
    }

    @BeforeEach
    void seed49() {
        db.execute("delete from consent_record");
        db.execute("insert into consent_record(member_id,doc_type,doc_version,scene,event_type,occurred_at,request_id,create_time) values(9,'privacy_policy','v-test','roam_location','AGREE',now(),'initial9',now())");
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
            Future<?> one = workers.submit(() -> { await(start); assertEquals(200, first.publish(100L,12L, "{}").get("code")); });
            start.countDown();
            assertTrue(firstCountReached.await(5, TimeUnit.SECONDS), "首请求未进入上限检查");
            Future<?> two = workers.submit(() -> assertEquals(200, second.publish(101L,12L, "{}").get("code")));
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
        assertEquals(200, controller(snapshots).publish(25L,12L, "{\"routeName\":\"已更新\"}").get("code"));
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
        ReflectionTestUtils.setField(target, "roamMapper", roam);
        com.chengyinhub.common.config.ComplianceProperties properties=new com.chengyinhub.common.config.ComplianceProperties();
        properties.setDocVersions(java.util.Map.of("privacy_policy","v-test"));
        ReflectionTestUtils.setField(target,"locationConsent",new com.chengyinhub.business.service.impl.RoamLocationConsentService(roam,consents,properties));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(controller, "snapshotMapper", mapper);
        ReflectionTestUtils.setField(controller, "privacyService", new RoamSharePrivacyService());
        ReflectionTestUtils.setField(controller, "snapshotService", proxy.getProxy());
        // 发布前文本送检(F-L5C-02)不在本并发用例范围内：送检桩放行，作者查询返回空。
        ReflectionTestUtils.setField(controller, "umsMemberService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
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

    @Test void revokedRoamLocationCannotUploadANewTrajectoryButStoredSnapshotRemainsReadable() {
        String existing=snapshots.selectSnapshotJsonByToken(String.format("%032x",1));
        assertNotNull(existing);
        db.update("insert into consent_record(member_id,doc_type,doc_version,scene,event_type,occurred_at,request_id,create_time) values(9,'privacy_policy','v-test','roam_location','REVOKE',now(),'revoke9',now())");
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                ()->controller(snapshots).publish(999L,12L,"{\"track\":[]}"));
        assertNull(snapshots.selectTokenByMemberTs(9L,999L));
        assertEquals(49,snapshots.countByMember(9L));
        assertEquals(existing,snapshots.selectSnapshotJsonByToken(String.format("%032x",1)));
    }
}
