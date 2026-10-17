package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN. Real MySQL/XML/Spring transaction; decorator injects only explicit failure boundaries. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_E02_15_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E02_15Test {
    private MysqlIntegrationTestConfig mysql;
    private boolean created;
    private JdbcTemplate jdbc;
    private CommunityPostServiceImpl service;
    private String fault;
    private final List<String> reached = new ArrayList<>();

    @BeforeAll
    void setup() throws Exception {
        for (String key : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",
                "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"}) {
            assertNotNull(System.getenv(key), "Explicit authorized scratch configuration required: " + key);
            if (!key.endsWith("PASSWORD")) assertFalse(System.getenv(key).trim().isEmpty(), key);
        }
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e02_15_it");
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(
                admin().queryForObject("select version()", String.class)), "MySQL 8+ required");
        // Collision fails: never drop or adopt a pre-existing schema.
        admin().execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
        created = true;
        DriverManagerDataSource ds = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(ds);
        schema();
        Configuration config = new Configuration(new Environment("e02-15", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        config.addMapper(CommunityPostMapper.class);
        String resource = "mapper/business/CommunityPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        CommunityPostMapper real = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config))
                .getMapper(CommunityPostMapper.class);
        CommunityPostMapper decorated = (CommunityPostMapper) Proxy.newProxyInstance(
                CommunityPostMapper.class.getClassLoader(), new Class<?>[]{CommunityPostMapper.class}, (proxy, method, args) -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), method.getName());
                    reached.add(method.getName());
                    if ("insertErasureJob".equals(method.getName()) && "JOB_ZERO".equals(fault)) {
                        assertDeletionAlreadyWritten(false);
                        return 0; // Intentional affected-row fault; preceding mutations use real XML.
                    }
                    if ("insertCommandReceipt".equals(method.getName()) && "RECEIPT_CONFLICT".equals(fault)) {
                        assertDeletionAlreadyWritten(true);
                        // Place a conflicting unique key after the replay precheck, on the same transaction.
                        // This tests real INSERT IGNORE + replay conflict and rollback, not concurrent scheduling.
                        jdbc.update("insert into community_post_command_receipt(member_id,request_id,command_type,aggregate_id,result_json,create_time) values (11,'e02-15-delete','POST_PUBLISH',7,'{}',now())");
                    }
                    Object result;
                    try { result = method.invoke(real, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                    if ("orphanMedia".equals(method.getName()) && "ORPHAN_THROW".equals(fault)) {
                        assertEquals(1, ((Number) result).intValue());
                        assertDeletionAlreadyWritten(false);
                        throw new ServiceException("synthetic failure after actual orphan mutation", 500);
                    }
                    return result;
                });
        CommunityPostServiceImpl target = new CommunityPostServiceImpl(decorated,
                new CommunityPostLifecyclePolicy(), null, null, null, null);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        service = (CommunityPostServiceImpl) proxy.getProxy();
    }

    @BeforeEach
    void seed() {
        for (String table : new String[]{"community_post_command_receipt", "community_post_outbox",
                "community_search_document", "community_erasure_job", "community_post_media", "community_post_topic", "community_post"}) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("insert into community_post(id,author_id,lifecycle,version,recommendation_eligible,search_eligible,interaction_enabled,title,body) values (7,11,'PUBLISHED',0,1,1,1,'synthetic title','synthetic body')");
        jdbc.update("insert into community_post_media(id,post_id,retention_state) values (9,7,'ACTIVE')");
        jdbc.update("insert into community_search_document(document_type,document_id,visibility_state,source_version) values ('POST',7,'VISIBLE',0)");
        fault = null;
        reached.clear();
    }

    @AfterAll
    void cleanup() {
        if (created) admin().execute("DROP DATABASE " + mysql.databaseName());
    }

    @Test
    void exceptionAfterRealMediaOrphanRollsBackLifecycleVersionAndRetention() {
        fault = "ORPHAN_THROW";
        ServiceException error = assertThrows(ServiceException.class, this::delete);
        assertEquals(Integer.valueOf(500), error.getCode());
        assertTrue(reached.contains("orphanMedia"));
        assertFalse(reached.contains("insertErasureJob"));
        assertRolledBack();
    }

    @Test
    void zeroErasureJobRowsRollBackAlreadyWrittenTransitionAndMedia() {
        fault = "JOB_ZERO";
        ServiceException error = assertThrows(ServiceException.class, this::delete);
        assertEquals(Integer.valueOf(500), error.getCode());
        assertTrue(reached.contains("insertErasureJob"));
        assertFalse(reached.contains("upsertSearchDocument"));
        assertRolledBack();
    }

    @Test
    void receiptConflictAfterProjectionAndOutboxRollsBackEntireDelete() {
        fault = "RECEIPT_CONFLICT";
        ServiceException error = assertThrows(ServiceException.class, this::delete);
        assertEquals(Integer.valueOf(409), error.getCode());
        assertTrue(reached.contains("insertCommandReceipt"));
        assertRolledBack();
    }

    @Test
    void healthyDeleteCommitsAllStagesAndExactReplayDoesNotRepeatThem() {
        delete();
        assertDeletionAlreadyWritten(true);
        assertEquals(1, count("community_post_command_receipt"));
        reached.clear();
        delete();
        assertFalse(reached.contains("transitionCas"));
        assertFalse(reached.contains("orphanMedia"));
        assertEquals(1, count("community_erasure_job"));
        assertEquals(1, count("community_post_outbox"));
        assertEquals(1, count("community_post_command_receipt"));
    }

    private void delete() {
        CommunityPostRequests.State request = new CommunityPostRequests.State();
        request.setExpectedVersion(0);
        request.setRequestId("e02-15-delete");
        service.delete(11L, 7L, request);
    }

    private void assertDeletionAlreadyWritten(boolean laterStages) {
        assertEquals("DELETED", jdbc.queryForObject("select lifecycle from community_post where id=7", String.class));
        assertEquals(1, jdbc.queryForObject("select version from community_post where id=7", Integer.class));
        assertEquals("ORPHANED", jdbc.queryForObject("select retention_state from community_post_media where id=9", String.class));
        assertNotNull(jdbc.queryForObject("select delete_after from community_post_media where id=9", java.sql.Timestamp.class));
        if (laterStages) {
            assertEquals(1, count("community_erasure_job"));
            assertEquals(1, count("community_post_outbox"));
            assertEquals("DELETED", jdbc.queryForObject("select visibility_state from community_search_document where document_id=7", String.class));
        }
    }

    private void assertRolledBack() {
        // Called after service proxy exits: fresh connections observe committed facts, not a manual rollback.
        assertEquals("PUBLISHED", jdbc.queryForObject("select lifecycle from community_post where id=7", String.class));
        assertEquals(0, jdbc.queryForObject("select version from community_post where id=7", Integer.class));
        assertNull(jdbc.queryForObject("select deleted_at from community_post where id=7", java.sql.Timestamp.class));
        assertEquals(1, jdbc.queryForObject("select interaction_enabled from community_post where id=7", Integer.class));
        assertEquals("ACTIVE", jdbc.queryForObject("select retention_state from community_post_media where id=9", String.class));
        assertNull(jdbc.queryForObject("select delete_after from community_post_media where id=9", java.sql.Timestamp.class));
        assertEquals("VISIBLE", jdbc.queryForObject("select visibility_state from community_search_document where document_id=7", String.class));
        assertEquals(0, jdbc.queryForObject("select source_version from community_search_document where document_id=7", Integer.class));
        assertEquals(0, count("community_erasure_job"));
        assertEquals(0, count("community_post_outbox"));
        assertEquals(0, count("community_post_command_receipt"));
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
    }

    private void schema() {
        jdbc.execute("create table community_post(id bigint primary key,author_id bigint,lifecycle varchar(16),version int,published_at datetime,deleted_at datetime,recommendation_eligible int,search_eligible int,interaction_enabled int,update_time datetime,title varchar(100),body text,city_code varchar(32)) engine=InnoDB");
        jdbc.execute("create table community_post_media(id bigint primary key,post_id bigint,retention_state varchar(16),delete_after datetime,update_time datetime) engine=InnoDB");
        jdbc.execute("create table community_post_topic(post_id bigint,topic_code varchar(64)) engine=InnoDB");
        jdbc.execute("create table community_erasure_job(id bigint auto_increment primary key,subject_member_id bigint,post_id bigint,trigger_type varchar(32),request_id varchar(96),legal_hold int,checklist_json json,state varchar(16),attempt_count int,next_attempt_at datetime,create_time datetime,update_time datetime) engine=InnoDB");
        jdbc.execute("create table community_search_document(document_type varchar(16),document_id bigint,owner_member_id bigint,title varchar(100),search_text text,city_code varchar(32),topic_codes_json json,visibility_state varchar(16),source_version int,indexed_at datetime,primary key(document_type,document_id)) engine=InnoDB");
        jdbc.execute("create table community_post_outbox(id bigint auto_increment primary key,event_id varchar(64) unique,aggregate_type varchar(32),aggregate_id bigint,event_type varchar(32),payload_json json,state varchar(16),attempt_count int,next_attempt_at datetime,create_time datetime,update_time datetime) engine=InnoDB");
        jdbc.execute("create table community_post_command_receipt(id bigint auto_increment primary key,member_id bigint,request_id varchar(96),command_type varchar(64),aggregate_id bigint,result_json json,create_time datetime,unique(member_id,request_id)) engine=InnoDB");
    }
}
