package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: real MySQL mapper and service transaction; no fake notification implementation. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_E02_20_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E02_20Test {
    private MysqlIntegrationTestConfig mysql;
    private boolean created;
    private JdbcTemplate jdbc;
    private CommunityPostServiceImpl service;
    private CommunityPostMapper mapper;
    private volatile java.util.concurrent.CyclicBarrier beforeUpsert;
    private volatile java.util.Date fixedUpsertTime;
    private volatile Integer observedServiceUpsertRows;

    @BeforeAll
    void setup() throws Exception {
        for (String key : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",
                "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"}) {
            assertNotNull(System.getenv(key), "Explicit authorized scratch configuration required: " + key);
            if (!key.endsWith("PASSWORD")) assertFalse(System.getenv(key).trim().isEmpty(), key);
        }
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e02_20_it");
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(
                admin().queryForObject("select version()", String.class)), "MySQL 8+ required");
        admin().execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
        created = true;
        DriverManagerDataSource ds = new DriverManagerDataSource(mysql.databaseUrl() + "&useAffectedRows=true", mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(ds);
        schema();
        Configuration c = new Configuration(new Environment("e02-20", new SpringManagedTransactionFactory(), ds));
        c.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        c.addMapper(CommunityPostMapper.class);
        String resource = "mapper/business/CommunityPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, c, resource, c.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c))
                .getMapper(CommunityPostMapper.class);
        CommunityPostMapper scheduled = (CommunityPostMapper) java.lang.reflect.Proxy.newProxyInstance(
                CommunityPostMapper.class.getClassLoader(), new Class<?>[]{CommunityPostMapper.class}, (proxyObject, method, args) -> {
                    java.util.concurrent.CyclicBarrier barrier = beforeUpsert;
                    if ("upsertNotificationPreferences".equals(method.getName()) && barrier != null) {
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    boolean freezeTime = "upsertNotificationPreferences".equals(method.getName()) && fixedUpsertTime != null;
                    Object[] actualArgs = args;
                    if (freezeTime) {
                        actualArgs = args.clone();
                        actualArgs[4] = fixedUpsertTime;
                    }
                    try {
                        Object result = method.invoke(mapper, actualArgs);
                        if (freezeTime) observedServiceUpsertRows = (Integer) result;
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
        CommunityPostServiceImpl target = new CommunityPostServiceImpl(scheduled,
                new CommunityPostLifecyclePolicy(), null, null, null, null);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        service = (CommunityPostServiceImpl) proxy.getProxy();
    }

    @BeforeEach
    void clearPreferences() {
        fixedUpsertTime = null;
        observedServiceUpsertRows = null;
        jdbc.update("delete from community_notification_preference");
    }

    @AfterAll
    void cleanup() { if (created) admin().execute("DROP DATABASE " + mysql.databaseName()); }

    @Test
    void concurrentDisjointPatchesPreserveBothChangesOnExistingRow() throws Exception {
        jdbc.update("insert into community_notification_preference values (11,1,1,0,now(),now())");
        concurrentPatches();
        assertStored(0, 0, 0);
    }

    @Test
    void concurrentFirstPatchesMergeWithDefaultsWithoutAnExistingRow() throws Exception {
        concurrentPatches();
        assertStored(0, 0, 1);
        assertEquals(1, jdbc.queryForObject("select count(*) from community_notification_preference", Integer.class));
    }

    @Test
    void partialPatchKeepsUnspecifiedFalseValuesAndGovernanceEnabled() {
        jdbc.update("insert into community_notification_preference values (11,0,1,0,now(),now())");
        Map<String, Object> result = service.updateNotificationPreferences(11L, patch(null, false, null));
        assertStored(0, 0, 0);
        assertEquals(Boolean.FALSE, result.get("interactionEnabled"));
        assertEquals(Boolean.FALSE, result.get("socialEnabled"));
        assertEquals(Boolean.TRUE, result.get("governanceEnabled"));
    }

    @Test
    void noOpZeroAffectedRowsAndEmptyPatchRemainSuccessful() {
        java.util.Date fixed = java.sql.Timestamp.valueOf("2020-01-02 03:04:05");
        assertEquals(1, mapper.upsertNotificationPreferences(11L, false, true, false, fixed));
        assertEquals(0, mapper.upsertNotificationPreferences(11L, null, null, null, fixed),
                "Affected-rows mode must expose the real MySQL no-op zero");
        fixedUpsertTime = fixed; // Deterministic clock only; the production XML and MySQL result are unchanged.
        Map<String, Object> result;
        try {
            result = service.updateNotificationPreferences(11L, patch(null, null, null));
        } finally {
            fixedUpsertTime = null;
        }
        assertEquals(Integer.valueOf(0), observedServiceUpsertRows,
                "The actual SQL call through the service must return zero, not merely a direct mapper setup call");
        assertStored(0, 1, 0);
        assertEquals(Boolean.FALSE, result.get("interactionEnabled"));
        assertEquals(Boolean.TRUE, result.get("mentionEnabled"));
        assertEquals(Boolean.FALSE, result.get("socialEnabled"));
        assertEquals(Boolean.TRUE, result.get("governanceEnabled"));
    }

    private void concurrentPatches() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        beforeUpsert = new java.util.concurrent.CyclicBarrier(2);
        try {
            java.util.concurrent.Future<Map<String, Object>> first = pool.submit(
                    () -> service.updateNotificationPreferences(11L, patch(false, null, null)));
            java.util.concurrent.Future<Map<String, Object>> second = pool.submit(
                    () -> service.updateNotificationPreferences(11L, patch(null, false, null)));
            assertEquals(Boolean.TRUE, first.get(20, java.util.concurrent.TimeUnit.SECONDS).get("governanceEnabled"));
            assertEquals(Boolean.TRUE, second.get(20, java.util.concurrent.TimeUnit.SECONDS).get("governanceEnabled"));
        } finally {
            beforeUpsert = null;
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS), "Workers must finish before schema cleanup");
        }
    }

    private com.chengyinhub.business.domain.dto.CommunityPostRequests.NotificationPreferences patch(
            Boolean interaction, Boolean mention, Boolean social) {
        com.chengyinhub.business.domain.dto.CommunityPostRequests.NotificationPreferences request =
                new com.chengyinhub.business.domain.dto.CommunityPostRequests.NotificationPreferences();
        request.setInteractionEnabled(interaction);
        request.setMentionEnabled(mention);
        request.setSocialEnabled(social);
        return request;
    }

    private void assertStored(int interaction, int mention, int social) {
        Map<String, Object> row = jdbc.queryForMap("select interaction_enabled,mention_enabled,social_enabled from community_notification_preference where member_id=11");
        assertEquals(interaction, ((Number) row.get("interaction_enabled")).intValue());
        assertEquals(mention, ((Number) row.get("mention_enabled")).intValue());
        assertEquals(social, ((Number) row.get("social_enabled")).intValue());
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
    }

    private void schema() {
        jdbc.execute("create table community_notification_preference(member_id bigint primary key,interaction_enabled tinyint not null,mention_enabled tinyint not null,social_enabled tinyint not null,create_time datetime,update_time datetime) engine=InnoDB");
    }
}
