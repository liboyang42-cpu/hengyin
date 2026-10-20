package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.community.CommunityPost;
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

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: real MySQL mapper and service transaction; no fake notification implementation. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_E02_18_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E02_18Test {
    private MysqlIntegrationTestConfig mysql;
    private boolean created;
    private JdbcTemplate jdbc;
    private CommunityPostServiceImpl service;

    @BeforeAll
    void setup() throws Exception {
        for (String key : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",
                "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"}) {
            assertNotNull(System.getenv(key), "Explicit authorized scratch configuration required: " + key);
            if (!key.endsWith("PASSWORD")) assertFalse(System.getenv(key).trim().isEmpty(), key);
        }
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e02_18_it");
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(
                admin().queryForObject("select version()", String.class)), "MySQL 8+ required");
        admin().execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
        created = true;
        DriverManagerDataSource ds = new DriverManagerDataSource(mysql.databaseUrl() + "&useAffectedRows=true", mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(ds);
        schema();
        Configuration c = new Configuration(new Environment("e02-18", new SpringManagedTransactionFactory(), ds));
        c.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        c.addMapper(CommunityPostMapper.class);
        String resource = "mapper/business/CommunityPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, c, resource, c.getSqlFragments()).parse();
        }
        CommunityPostMapper mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c))
                .getMapper(CommunityPostMapper.class);
        CommunityPostServiceImpl target = new CommunityPostServiceImpl(mapper,
                new CommunityPostLifecyclePolicy(), null, null, null, null);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        service = (CommunityPostServiceImpl) proxy.getProxy();
    }

    @AfterAll
    void cleanup() { if (created) admin().execute("DROP DATABASE " + mysql.databaseName()); }

    @Test
    void existingGovernanceProjectionUpdateCompletesOutboxWithoutRetry() {
        jdbc.update("insert into community_member_governance_state(member_id,last_event_type,last_event_payload_json,last_event_at,version,create_time,update_time) values (11,'OLD','{}',now(),0,now(),now())");
        jdbc.update("insert into community_post_outbox(id,event_id,aggregate_type,aggregate_id,event_type,payload_json,state,attempt_count,next_attempt_at,create_time,update_time) values (1,'synthetic-member-update','COMMUNITY_MEMBER',11,'ACCOUNT_APPEAL_SUBMITTED','{\"enforcementId\":31}','PENDING',0,now(),now(),now())");
        Map<String, Integer> result = service.runMaintenance(20);
        assertEquals(Integer.valueOf(1), result.get("outbox"));
        Map<String, Object> event = jdbc.queryForMap("select state,attempt_count,last_error_code from community_post_outbox where id=1");
        assertEquals("DONE", event.get("state"));
        assertEquals(1, ((Number) event.get("attempt_count")).intValue());
        assertNull(event.get("last_error_code"));
        assertEquals("ACCOUNT_APPEAL_SUBMITTED", jdbc.queryForObject("select last_event_type from community_member_governance_state where member_id=11", String.class));
        assertEquals(1L, jdbc.queryForObject("select version from community_member_governance_state where member_id=11", Long.class));
        assertEquals(Integer.valueOf(0), service.runMaintenance(20).get("outbox"));
        assertEquals(1L, jdbc.queryForObject("select version from community_member_governance_state where member_id=11", Long.class), "Completed event must not reapply on the next pass");
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
    }

    private void schema() {
        jdbc.execute("create table community_member_governance_state(member_id bigint primary key,last_event_type varchar(80),last_event_payload_json json,last_event_at datetime,version bigint,create_time datetime,update_time datetime) engine=InnoDB");
        jdbc.execute("create table community_post_outbox(id bigint primary key,event_id varchar(36),aggregate_type varchar(40),aggregate_id bigint,event_type varchar(80),payload_json json,state varchar(20),attempt_count int,next_attempt_at datetime,create_time datetime,update_time datetime,processed_at datetime,last_error_code varchar(100)) engine=InnoDB");
        jdbc.execute("create table community_post_enforcement(post_id bigint,status varchar(20),starts_at datetime,ends_at datetime,update_time datetime) engine=InnoDB");
        jdbc.execute("create table community_erasure_job(id bigint,subject_member_id bigint,post_id bigint,trigger_type varchar(40),legal_hold int,attempt_count int,state varchar(30),next_attempt_at datetime) engine=InnoDB");
        jdbc.execute("create table community_guideline_version(id bigint,version_code varchar(40),title varchar(120),summary varchar(255),content_json json,effective_at datetime,applies_to varchar(20),status varchar(20)) engine=InnoDB");
    }
}
