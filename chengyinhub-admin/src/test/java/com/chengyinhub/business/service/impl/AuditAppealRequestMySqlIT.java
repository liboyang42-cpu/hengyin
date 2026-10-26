package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditAppeal;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** Three real MySQL writes + once-only keys. Only unrelated content/media collaborators are fixture mocks. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditAppealRequestMySqlIT {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private AuditTaskServiceImpl service;
    private String database;

    @BeforeEach
    void setup() throws Exception {
        assertTrue("127.0.0.1".equals(MybatisItDatabase.host()) || "localhost".equals(MybatisItDatabase.host()), "scratch localhost only");
        String url = MybatisItDatabase.url("audit_appeal_request");
        database = MybatisItDatabase.databaseName("audit_appeal_request");
        DriverManagerDataSource source = new DriverManagerDataSource(url, MybatisItDatabase.user(), MybatisItDatabase.password());
        jdbc = new JdbcTemplate(source);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(jdbc.queryForObject("SELECT VERSION()", String.class)));
        for (String table : new String[]{"audit_record", "audit_appeal", "audit_task"}) jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE audit_task(id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_type VARCHAR(32),biz_id BIGINT,submitter_id BIGINT,source_type VARCHAR(32),risk_level VARCHAR(32),status INT,current_assignee BIGINT,submitted_at DATETIME,deadline_at DATETIME,snapshot_json LONGTEXT,version INT,create_by VARCHAR(64),create_time DATETIME,update_by VARCHAR(64),update_time DATETIME,remark VARCHAR(500),open_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status IN(0,3) THEN 1 ELSE NULL END) STORED,UNIQUE KEY uk_audit_biz_open(biz_type,biz_id,open_guard)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE audit_record(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,action VARCHAR(32),operator_id BIGINT,reason_code VARCHAR(64),reason_text VARCHAR(500),snapshot_json LONGTEXT,reverted_record_id BIGINT,reason_codes_json LONGTEXT,create_time DATETIME) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE audit_appeal(id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_type VARCHAR(32),biz_id BIGINT,task_id BIGINT,member_id BIGINT,reason VARCHAR(500),evidence_json LONGTEXT,state VARCHAR(32),due_at DATETIME,handled_by BIGINT,handled_at DATETIME,created_at DATETIME,UNIQUE KEY once_per_content(biz_type,biz_id,member_id),UNIQUE KEY one_task(task_id)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO audit_task(id,biz_type,biz_id,submitter_id,source_type,status,version,snapshot_json) VALUES(70,'ACTIVITY',501,10,'user_publish',2,1,'{}')");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/AuditTaskMapper.xml"),
                new ClassPathResource("mapper/business/AuditRecordMapper.xml"), new ClassPathResource("mapper/business/AuditAppealMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        AuditAppealAttachmentTest fixture = new AuditAppealAttachmentTest(); fixture.setUp();
        service = (AuditTaskServiceImpl) ReflectionTestUtils.getField(fixture, "service");
        ReflectionTestUtils.setField(service, "auditTaskMapper", session.getMapper(AuditTaskMapper.class));
        ReflectionTestUtils.setField(service, "auditRecordMapper", session.getMapper(AuditRecordMapper.class));
        ReflectionTestUtils.setField(service, "auditAppealMapper", session.getMapper(AuditAppealMapper.class));
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterAll
    void cleanup() { if (jdbc != null && database != null) jdbc.execute("DROP DATABASE " + database); }
    private AuditAppeal submit(String key, String evidence) {
        return tx.execute(status -> service.submitAppeal("ACTIVITY", 501L, 10L, "理由", evidence, Collections.emptyList(), key));
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test
    void replayAndOwnerReceiptUseCommittedSubmitSnapshotAndPreserveArrayEvidence() {
        AuditAppeal first = submit("original", "[{\"requestId\":\"client\"}]");
        AuditAppeal replay = submit("original", "[{\"requestId\":\"client\"}]");
        assertEquals(first.getId(), replay.getId()); assertEquals(first.getTaskId(), replay.getTaskId());
        assertEquals("[{\"requestId\":\"client\"}]", jdbc.queryForObject("SELECT evidence_json FROM audit_appeal", String.class));
        assertEquals(2, count("audit_task")); assertEquals(1, count("audit_appeal")); assertEquals(1, count("audit_record"));
        assertEquals("FOUND", service.appealReceipt("ACTIVITY", 501L, 10L, "original").get("state"));
        assertEquals("NOT_FOUND", service.appealReceipt("ACTIVITY", 501L, 11L, "original").get("state"));
        assertThrows(ServiceException.class, () -> submit("original", "[]"));
        assertThrows(ServiceException.class, () -> submit("different", "[{\"requestId\":\"client\"}]"));
        assertEquals(1, count("audit_appeal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"audit_task", "audit_appeal", "audit_record"})
    void failureAtEachWriteRollsBackAllThree(String table) {
        jdbc.execute("CREATE TRIGGER reject_appeal_write BEFORE INSERT ON " + table + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected write failure'");
        assertThrows(RuntimeException.class, () -> submit("rollback", "[]"));
        assertEquals(1, count("audit_task")); assertEquals(0, count("audit_appeal")); assertEquals(0, count("audit_record"));
        assertEquals("NOT_FOUND", service.appealReceipt("ACTIVITY", 501L, 10L, "rollback").get("state"));
    }

    @Test
    void concurrentSameRequestHasOneTaskAndConflictRecoversOnlyThroughExactReceipt() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> call = () -> {
            start.await();
            try { submit("same", "[]"); return true; }
            catch (ServiceException conflict) { assertEquals(409, conflict.getCode()); return false; }
            catch (org.springframework.dao.ConcurrencyFailureException conflict) { return false; }
        };
        try {
            Future<Boolean> a = pool.submit(call), b = pool.submit(call); start.countDown();
            boolean first = a.get(10, TimeUnit.SECONDS), second = b.get(10, TimeUnit.SECONDS); assertTrue(first || second);
            assertEquals("FOUND", service.appealReceipt("ACTIVITY", 501L, 10L, "same").get("state"));
            assertEquals("NOT_FOUND", service.appealReceipt("ACTIVITY", 501L, 10L, "other").get("state"));
            assertEquals(2, count("audit_task")); assertEquals(1, count("audit_appeal")); assertEquals(1, count("audit_record"));
        } finally { pool.shutdownNow(); }
    }
}
