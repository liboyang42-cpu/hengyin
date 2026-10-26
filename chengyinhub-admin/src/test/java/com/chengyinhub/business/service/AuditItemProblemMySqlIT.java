package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.support.AuditProblemService;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Collections;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL decision CAS, record/problem/event transaction; unrelated content/notification collaborators use existing fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditItemProblemMySqlIT {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private AuditTaskServiceImpl service;
    private String database;

    @BeforeEach
    void setup() throws Exception {
        assertTrue("127.0.0.1".equals(MybatisItDatabase.host()) || "localhost".equals(MybatisItDatabase.host()), "scratch localhost only");
        String url = MybatisItDatabase.url("audit_item_problem");
        database = MybatisItDatabase.databaseName("audit_item_problem");
        DriverManagerDataSource source = new DriverManagerDataSource(url, MybatisItDatabase.user(), MybatisItDatabase.password());
        jdbc = new JdbcTemplate(source);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(jdbc.queryForObject("SELECT VERSION()", String.class)));
        for (String table : new String[]{"audit_problem", "audit_record", "business_audit_event", "audit_task"}) jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE audit_task(id BIGINT PRIMARY KEY,biz_type VARCHAR(32),biz_id BIGINT,submitter_id BIGINT,source_type VARCHAR(32),risk_level VARCHAR(32),status INT,current_assignee BIGINT,submitted_at DATETIME,deadline_at DATETIME,snapshot_json LONGTEXT,version INT,create_by VARCHAR(64),create_time DATETIME,update_by VARCHAR(64),update_time DATETIME,remark VARCHAR(500)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE audit_record(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,action VARCHAR(32),operator_id BIGINT,reason_code VARCHAR(64),reason_text VARCHAR(500),snapshot_json LONGTEXT,reverted_record_id BIGINT,reason_codes_json LONGTEXT,create_time DATETIME) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE audit_problem(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,record_id BIGINT,biz_type VARCHAR(32),biz_id BIGINT,owner_member_id BIGINT,item_type VARCHAR(32),item_id BIGINT,reason_code VARCHAR(64),reason_text VARCHAR(200),create_time DATETIME) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE business_audit_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,domain_type VARCHAR(32),domain_id BIGINT,owner_member_id BIGINT,actor_type VARCHAR(32),actor_member_id BIGINT,actor_role_code VARCHAR(64),action VARCHAR(64),target_type VARCHAR(32),target_id BIGINT,relation_id BIGINT,before_json LONGTEXT,after_json LONGTEXT,reason VARCHAR(500),request_id VARCHAR(64),create_time DATETIME,UNIQUE KEY request_key(domain_type,domain_id,action,request_id)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO audit_task(id,biz_type,biz_id,submitter_id,source_type,status,version) VALUES(9,'TOPIC',77,5,'post_review_flag',0,0)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/AuditTaskMapper.xml"),
                new ClassPathResource("mapper/business/AuditRecordMapper.xml"),
                new ClassPathResource("mapper/business/AuditProblemMapper.xml"),
                new ClassPathResource("mapper/business/BusinessAuditEventMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        AuditItemProblemTest fixture = new AuditItemProblemTest();
        fixture.setUp();
        service = (AuditTaskServiceImpl) ReflectionTestUtils.getField(fixture, "service");
        ReflectionTestUtils.setField(service, "auditTaskMapper", session.getMapper(AuditTaskMapper.class));
        ReflectionTestUtils.setField(service, "auditRecordMapper", session.getMapper(AuditRecordMapper.class));
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", session.getMapper(BusinessAuditEventMapper.class));
        ReflectionTestUtils.setField(service, "auditProblemService", new AuditProblemService(session.getMapper(AuditProblemMapper.class),
                (CmsTopicChapterMapper) ReflectionTestUtils.getField(fixture, "chapterMapper"),
                (CmsTopicNodeMapper) ReflectionTestUtils.getField(fixture, "nodeMapper")));
        ReflectionTestUtils.setField(service, "itemProblemsEnabled", true);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterAll
    void cleanup() { if (jdbc != null && database != null) jdbc.execute("DROP DATABASE " + database); }

    private AuditReviewDTO command(String text) {
        AuditReviewDTO request = new AuditReviewDTO();
        request.setStatus(2); request.setReasonText("需要修改"); request.setRequestId("mysql-item-intent");
        AuditReviewDTO.ProblemItem item = new AuditReviewDTO.ProblemItem();
        item.setItemType("NODE"); item.setItemId(41L); item.setReasonText(text);
        request.setProblems(Collections.singletonList(item));
        return request;
    }
    private int review(String text) { return tx.execute(status -> service.review(9L, 1L, command(text))); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test
    void committedDecisionReplaysOnceAndOriginalReviewerReadsPersistedItems() {
        assertEquals(1, review("节点问题")); assertEquals(1, review("节点问题"));
        assertEquals(1, count("audit_record")); assertEquals(1, count("audit_problem")); assertEquals(1, count("business_audit_event"));
        assertEquals("FOUND", service.itemProblemReceipt(9L, 1L, "mysql-item-intent").get("state"));
        assertThrows(RuntimeException.class, () -> review("改变输入"));
        assertEquals(1, count("audit_problem"));
    }

    @Test
    void eventFailureRollsBackCasRecordAndProblems() {
        jdbc.execute("CREATE TRIGGER deny_item_event BEFORE INSERT ON business_audit_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected audit write failure'");
        assertThrows(RuntimeException.class, () -> review("节点问题"));
        assertEquals(0, count("audit_record")); assertEquals(0, count("audit_problem")); assertEquals(0, count("business_audit_event"));
        assertEquals(0, jdbc.queryForObject("SELECT version FROM audit_task WHERE id=9", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM audit_task WHERE id=9", Integer.class));
    }

    @Test
    void concurrentSameIntentHasOneEffectAndCanRecoverAfterConflict() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> submit = () -> { start.await(); try { review("节点问题"); return true; } catch (org.springframework.dao.ConcurrencyFailureException expected) { return false; } };
        try {
            Future<Boolean> a = pool.submit(submit), b = pool.submit(submit); start.countDown();
            boolean first = a.get(10, TimeUnit.SECONDS), second = b.get(10, TimeUnit.SECONDS);
            assertTrue(first || second);
            assertEquals(1, review("节点问题"));
            assertEquals(1, count("audit_record")); assertEquals(1, count("audit_problem")); assertEquals(1, count("business_audit_event"));
        } finally { pool.shutdownNow(); }
    }
}
