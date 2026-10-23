package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.vo.AuditTrailEntryVO;
import com.chengyinhub.business.mapper.AuditTrailMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.AuditTrailProjectionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOC2 O5c-(a):统一留痕读投影的真 SQL(MySQL 8,一类一库)。
 * 表结构取自各自 migration 的列定义(去掉与本查询无关的外键),各表 collation 故意不一致 ——
 * 与生产一样,证明分表查询 + Java 合并不会撞 Illegal mix of collations。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditTrailProjectionMySqlIT {

    private String database;
    private JdbcTemplate admin;
    private AuditTrailProjectionService service;

    @BeforeAll
    void setup() throws Exception {
        assertTrue("127.0.0.1".equals(MybatisItDatabase.host()) || "localhost".equals(MybatisItDatabase.host()),
                "Only localhost scratch MySQL");
        String url = MybatisItDatabase.url("audit_trail_o5c");
        database = MybatisItDatabase.databaseName("audit_trail_o5c");
        admin = new JdbcTemplate(new DriverManagerDataSource(url.replace("/" + database + "?", "/mysql?"),
                MybatisItDatabase.user(), MybatisItDatabase.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()", String.class)));
        DriverManagerDataSource source = new DriverManagerDataSource(url, MybatisItDatabase.user(), MybatisItDatabase.password());
        try (Connection connection = source.getConnection(); Statement st = connection.createStatement()) {
            schema(st);
            fixtures(st);
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/AuditTrailMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        service = new AuditTrailProjectionService(session.getMapper(AuditTrailMapper.class));
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @AfterAll
    void cleanup() {
        if (database != null) {
            admin.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void topicTrailMergesAuditRecordsAndDecisionEventsAcrossTasksInTimeOrder() {
        List<AuditTrailEntryVO> trail = service.trail("TOPIC", 10L);
        assertEquals(Arrays.asList(
                "AUDIT_RECORD#1:SUBMIT", "AUDIT_RECORD#2:REJECT", "BUSINESS_AUDIT_EVENT#1:AUDIT_DECISION_REJECTED",
                "AUDIT_RECORD#3:APPROVE", "BUSINESS_AUDIT_EVENT#2:AUDIT_DECISION_APPROVED"), keys(trail));
        AuditTrailEntryVO reject = trail.get(1);
        assertEquals(100L, reject.getRefId());
        assertEquals(9001L, reject.getOperatorId());
        assertEquals("BAD_COVER", reject.getReasonCode());
        assertEquals("SYS_USER", trail.get(2).getOperatorType());
    }

    @Test
    void otherItemsAndOtherBizTypesDoNotLeakIn() {
        assertEquals(Arrays.asList("AUDIT_RECORD#4:APPROVE"), keys(service.trail("TOPIC", 11L)));
        assertTrue(service.trail("TEMPLATE", 10L).isEmpty());
    }

    @Test
    void communityPostTrailIncludesEnforcementAndContentReportTimelineOnly() {
        List<AuditTrailEntryVO> trail = service.trail("COMMUNITY_POST", 20L);
        assertEquals(Arrays.asList(
                "REPORT_TIMELINE#1:REPORTER_SUBMITTED:SUBMITTED",
                "AUDIT_RECORD#5:REJECT",
                "POST_ENFORCEMENT#1:HIDE_PENDING_REVIEW:ACTIVE",
                "REPORT_TIMELINE#2:DECIDED:RESOLVED"), keys(trail));
        AuditTrailEntryVO report = trail.get(0);
        assertEquals(500L, report.getRefId());
        assertNull(report.getOperatorId(), "举报时间线不投影举报人身份");
        assertEquals("已收到举报", report.getReasonText(), "只读 public_note,不读 internal_note");
    }

    @Test
    void activityAndOfficialEventPickTheirOwnSources() {
        assertEquals(Arrays.asList("REPORT_TIMELINE#3:REPORTER_SUBMITTED:SUBMITTED"), keys(service.trail("ACTIVITY", 20L)));
        assertEquals(Arrays.asList("OFFICIAL_EVENT_AUDIT_LOG#1:SUBMITTED_FOR_REVIEW", "AUDIT_RECORD#6:APPROVE",
                "OFFICIAL_EVENT_AUDIT_LOG#2:SCHEDULED"), keys(service.trail("OFFICIAL_EVENT", 30L)));
    }

    private static List<String> keys(List<AuditTrailEntryVO> trail) {
        return trail.stream().map(e -> e.getSource() + "#" + e.getSourceId() + ":" + e.getAction())
                .collect(Collectors.toList());
    }

    private static void fixtures(Statement st) throws Exception {
        st.execute("INSERT INTO audit_task(id,biz_type,biz_id,submitter_id,status,submitted_at) VALUES"
                + "(100,'TOPIC',10,7,2,'2026-10-01 09:00:00'),(101,'TOPIC',10,7,1,'2026-10-02 09:00:00'),"
                + "(102,'TOPIC',11,8,1,'2026-10-01 09:00:00'),(103,'COMMUNITY_POST',20,7,2,'2026-10-03 09:00:00'),"
                + "(104,'OFFICIAL_EVENT',30,7,1,'2026-10-04 09:00:00')");
        st.execute("INSERT INTO audit_record(id,task_id,action,operator_id,reason_code,reason_text,snapshot_json,create_time) VALUES"
                + "(1,100,'SUBMIT',7,NULL,NULL,'{\"secret\":1}','2026-10-01 09:00:00'),"
                + "(2,100,'REJECT',9001,'BAD_COVER','封面不合规',NULL,'2026-10-01 10:00:00'),"
                + "(3,101,'APPROVE',9002,NULL,NULL,NULL,'2026-10-02 10:00:00'),"
                + "(4,102,'APPROVE',9002,NULL,NULL,NULL,'2026-10-01 11:00:00'),"
                + "(5,103,'REJECT',9001,'SPAM','广告',NULL,'2026-10-03 10:00:00'),"
                + "(6,104,'APPROVE',9002,NULL,NULL,NULL,'2026-10-04 10:00:00')");
        st.execute("INSERT INTO business_audit_event(id,domain_type,domain_id,owner_member_id,actor_type,actor_member_id,"
                + "actor_role_code,action,target_type,target_id,reason,request_id,create_time) VALUES"
                + "(1,'PLATFORM',100,7,'SYS_USER',9001,'auditor','AUDIT_DECISION_REJECTED','AUDIT_TASK',100,'封面不合规','r1','2026-10-01 10:00:00'),"
                + "(2,'PLATFORM',101,7,'SYS_USER',9002,'auditor','AUDIT_DECISION_APPROVED','AUDIT_TASK',101,NULL,'r2','2026-10-02 10:00:00'),"
                // 同 target_id 但不是审核域的事件不得混入
                + "(3,'MERCHANT',100,7,'MEMBER',7,'OWNER','MERCHANT_EDIT','AUDIT_TASK',100,NULL,'r3','2026-10-01 10:30:00')");
        st.execute("INSERT INTO community_post_enforcement(id,post_id,enforcement_type,rule_code,guideline_version_id,reason_code,"
                + "evidence_snapshot_json,operator_user_id,starts_at,status,request_id,create_time,update_time) VALUES"
                + "(1,20,'HIDE_PENDING_REVIEW','R1',1,'SPAM','{}',9001,'2026-10-03 10:30:00','ACTIVE','e1','2026-10-03 10:30:00','2026-10-03 10:30:00'),"
                + "(2,21,'REMOVE','R1',1,'SPAM','{}',9001,'2026-10-03 10:30:00','ACTIVE','e2','2026-10-03 10:30:00','2026-10-03 10:30:00')");
        st.execute("INSERT INTO community_report_case(id,reporter_member_id,target_type,target_id,reason_code,description,"
                + "evidence_asset_ids_json,anonymity_policy,stage,request_id,payload_hash,create_time,update_time) VALUES"
                + "(500,4242,'CONTENT',20,'SPAM','x','[]','SUBJECT_HIDDEN','RESOLVED','q1',REPEAT('a',64),'2026-10-03 08:00:00','2026-10-03 11:00:00'),"
                + "(501,4242,'ACTIVITY',20,'FRAUD','x','[]','SUBJECT_HIDDEN','SUBMITTED','q2',REPEAT('b',64),'2026-10-03 08:00:00','2026-10-03 08:00:00')");
        st.execute("INSERT INTO community_report_timeline(id,report_id,event_key,payload_hash,actor_type,event_type,from_stage,"
                + "to_stage,public_note,internal_note,create_time) VALUES"
                + "(1,500,'k1',REPEAT('a',64),'REPORTER','REPORTER_SUBMITTED',NULL,'SUBMITTED','已收到举报','举报人4242','2026-10-03 08:00:00'),"
                + "(2,500,'k2',REPEAT('a',64),'PLATFORM','DECIDED','SUBMITTED','RESOLVED','已处理',NULL,'2026-10-03 11:00:00'),"
                + "(3,501,'k3',REPEAT('b',64),'REPORTER','REPORTER_SUBMITTED',NULL,'SUBMITTED','已收到举报',NULL,'2026-10-03 08:00:00')");
        st.execute("INSERT INTO official_event_audit_log(id,event_id,action,operator_id,summary,before_json,after_json,create_time) VALUES"
                + "(1,30,'SUBMITTED_FOR_REVIEW',7,'发布检查通过',NULL,NULL,'2026-10-04 09:00:00'),"
                + "(2,30,'SCHEDULED',9002,'已排期',NULL,'{\"x\":1}','2026-10-04 11:00:00'),"
                + "(3,31,'SCHEDULED',9002,'别的活动',NULL,NULL,'2026-10-04 11:00:00')");
    }

    private static void schema(Statement st) throws Exception {
        st.execute("CREATE TABLE audit_task (id bigint NOT NULL AUTO_INCREMENT, biz_type varchar(40) NOT NULL,"
                + " biz_id bigint NOT NULL, submitter_id bigint DEFAULT NULL, source_type varchar(40) DEFAULT NULL,"
                + " risk_level varchar(20) NOT NULL DEFAULT 'NORMAL', status tinyint NOT NULL DEFAULT '0',"
                + " submitted_at datetime NOT NULL, snapshot_json mediumtext, version int NOT NULL DEFAULT '0',"
                + " PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
        st.execute("CREATE TABLE audit_record (id bigint NOT NULL AUTO_INCREMENT, task_id bigint NOT NULL,"
                + " action varchar(40) NOT NULL, operator_id bigint DEFAULT NULL, reason_code varchar(60) DEFAULT NULL,"
                + " reason_text varchar(500) DEFAULT NULL, snapshot_json mediumtext, create_time datetime DEFAULT NULL,"
                + " PRIMARY KEY (id), KEY idx_audit_record_task (task_id, create_time))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
        st.execute("CREATE TABLE business_audit_event (id BIGINT NOT NULL AUTO_INCREMENT, domain_type VARCHAR(16) NOT NULL,"
                + " domain_id BIGINT NOT NULL, owner_member_id BIGINT NOT NULL, actor_type VARCHAR(16) NOT NULL DEFAULT 'MEMBER',"
                + " actor_member_id BIGINT NOT NULL, actor_role_code VARCHAR(32) NOT NULL, action VARCHAR(64) NOT NULL,"
                + " target_type VARCHAR(32) NOT NULL, target_id BIGINT DEFAULT NULL, relation_id BIGINT DEFAULT NULL,"
                + " before_json JSON DEFAULT NULL, after_json JSON DEFAULT NULL, reason VARCHAR(500) DEFAULT NULL,"
                + " request_id VARCHAR(64) DEFAULT NULL, create_time DATETIME NOT NULL, PRIMARY KEY (id),"
                + " KEY idx_business_audit_target (target_type, target_id, create_time))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        st.execute("CREATE TABLE community_post_enforcement (id BIGINT NOT NULL AUTO_INCREMENT, post_id BIGINT NULL,"
                + " target_member_id BIGINT NULL, enforcement_type VARCHAR(32) NOT NULL, rule_code VARCHAR(64) NOT NULL,"
                + " guideline_version_id BIGINT NOT NULL, reason_code VARCHAR(64) NOT NULL, previous_lifecycle VARCHAR(16) NULL,"
                + " evidence_snapshot_json JSON NOT NULL, operator_user_id BIGINT NOT NULL, starts_at DATETIME NOT NULL,"
                + " ends_at DATETIME NULL, status VARCHAR(16) NOT NULL, request_id VARCHAR(96) NOT NULL,"
                + " version INT UNSIGNED NOT NULL DEFAULT 0, create_time DATETIME NOT NULL, update_time DATETIME NOT NULL,"
                + " PRIMARY KEY (id), KEY idx_community_enforcement_post (post_id, status, id))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        st.execute("CREATE TABLE community_report_case (id BIGINT NOT NULL AUTO_INCREMENT, reporter_member_id BIGINT NOT NULL,"
                + " target_type VARCHAR(32) NOT NULL, target_id BIGINT NOT NULL, reason_code VARCHAR(32) NOT NULL,"
                + " description VARCHAR(2000) NOT NULL, evidence_asset_ids_json JSON NOT NULL, anonymity_policy VARCHAR(32) NOT NULL,"
                + " stage VARCHAR(32) NOT NULL, request_id VARCHAR(96) NOT NULL, payload_hash CHAR(64) NOT NULL,"
                + " version INT NOT NULL DEFAULT 0, create_time DATETIME NOT NULL, update_time DATETIME NOT NULL,"
                + " PRIMARY KEY (id), KEY idx_community_report_subject (target_type, target_id, stage))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        st.execute("CREATE TABLE community_report_timeline (id BIGINT NOT NULL AUTO_INCREMENT, report_id BIGINT NOT NULL,"
                + " event_key VARCHAR(128) NOT NULL, payload_hash CHAR(64) NOT NULL, actor_type VARCHAR(16) NOT NULL,"
                + " event_type VARCHAR(32) NOT NULL, from_stage VARCHAR(32) NULL, to_stage VARCHAR(32) NOT NULL,"
                + " public_note VARCHAR(500) NOT NULL, internal_note VARCHAR(1000) NULL, create_time DATETIME NOT NULL,"
                + " PRIMARY KEY (id), KEY idx_community_report_timeline (report_id, create_time, id))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        st.execute("CREATE TABLE official_event_audit_log (id bigint NOT NULL AUTO_INCREMENT, event_id bigint NOT NULL,"
                + " action varchar(64) NOT NULL, operator_id bigint DEFAULT NULL, summary varchar(500) DEFAULT NULL,"
                + " before_json text, after_json text, create_time datetime NOT NULL, PRIMARY KEY (id),"
                + " KEY idx_event_time (event_id, create_time)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
    }
}
