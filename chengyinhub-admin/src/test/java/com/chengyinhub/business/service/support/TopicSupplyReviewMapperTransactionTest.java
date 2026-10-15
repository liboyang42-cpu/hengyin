package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 实际生产 Mapper 的版本取消及失败回滚；不冒充完整供给业务验收。 */
class TopicSupplyReviewMapperTransactionTest {
    private SqlSessionFactory factory;
    @BeforeEach void prepare() throws Exception {
        Configuration config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("AuditTask", AuditTask.class);
        config.getTypeAliasRegistry().registerAlias("AuditRecord", AuditRecord.class);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:supply_review_tx;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")));
        for (String name : new String[]{"AuditTaskMapper", "AuditRecordMapper"}) {
            String resource = "mapper/business/" + name + ".xml";
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        factory = new SqlSessionFactoryBuilder().build(config);
        try (SqlSession session = factory.openSession(true); Statement st = session.getConnection().createStatement()) {
            st.execute("drop table if exists audit_record"); st.execute("drop table if exists audit_task");
            st.execute("create table audit_task(id bigint primary key,biz_type varchar(50),biz_id bigint,submitter_id bigint,source_type varchar(50),risk_level varchar(60),status int,current_assignee bigint,submitted_at timestamp,deadline_at timestamp,snapshot_json text,version int,create_by varchar(40),create_time timestamp,update_by varchar(40),update_time timestamp,remark varchar(100))");
            st.execute("create table audit_record(id bigint auto_increment primary key,task_id bigint,action varchar(30),operator_id bigint,reason_code varchar(30),reason_text varchar(500),snapshot_json text,reverted_record_id bigint,create_time timestamp)");
            st.execute("insert into audit_task(id,biz_type,biz_id,submitter_id,status,version,snapshot_json) values(1,'TOPIC',10,7,0,5,'topic-v5'),(2,'TOPIC_PROJECT_REVIEW',10,7,3,5,'project-v5'),(3,'TOPIC_SALE_REVIEW',10,7,0,5,'sale-v5'),(4,'ACTIVITY',10,7,0,5,'activity')");
        }
    }
    @Test void threeCancelledVersionsAndAuditsPersistWithoutTouchingOtherDomain() throws Exception {
        try (SqlSession session = factory.openSession(false)) {
            AuditTaskMapper tasks = session.getMapper(AuditTaskMapper.class);
            TopicSupplyReviewService service = service(tasks, session.getMapper(AuditRecordMapper.class));
            assertEquals(3, tasks.selectTopicPublicationTasksForUpdate(10L).size());
            assertEquals(0, tasks.cancelTopicPublicationTaskCas(1L, 999));
            assertTrue(service.invalidate(10L, 88L)); session.commit();
            assertFalse(service.invalidate(10L, 88L));
            assertEquals(3, count(session, "select count(*) from audit_task where status=4 and version=6 and risk_level='SUPPLY_REPLACEMENT'"));
            assertEquals(3, count(session, "select count(*) from audit_record where action='CANCEL' and operator_id=88"));
            assertEquals(1, count(session, "select count(*) from audit_task where id=4 and status=0 and version=5"));
            assertEquals("topic-v5", tasks.selectAuditTaskById(1L).getSnapshotJson());
        }
    }
    @Test void failedSecondAuditRollsBackEveryTaskAndFirstAudit() throws Exception {
        try (SqlSession session = factory.openSession(false)) {
            AuditRecordMapper actual = session.getMapper(AuditRecordMapper.class), records = mock(AuditRecordMapper.class);
            when(records.insertAuditRecord(any())).thenAnswer(call -> {
                AuditRecord record = call.getArgument(0); return record.getTaskId().equals(2L) ? 0 : actual.insertAuditRecord(record);
            });
            assertThrows(IllegalStateException.class, () -> service(session.getMapper(AuditTaskMapper.class), records).invalidate(10L, 88L));
            session.rollback();
        }
        try (SqlSession read = factory.openSession(true)) {
            assertEquals(4, count(read, "select count(*) from audit_task where version=5 and status in(0,3)"));
            assertEquals(0, count(read, "select count(*) from audit_record"));
        }
    }
    private TopicSupplyReviewService service(AuditTaskMapper tasks, AuditRecordMapper records) {
        return new TopicSupplyReviewService(tasks, records, mock(CmsTopicMapper.class), mock(CmsTopicChapterMapper.class));
    }
    private int count(SqlSession session, String sql) throws Exception {
        try (Statement st = session.getConnection().createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getInt(1); }
    }
}
