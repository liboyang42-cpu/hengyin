package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.io.InputStream;
import java.util.Date;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Shared contracts run against real mapper SQL on H2 and separately gated Oracle MySQL. */
abstract class ContentCaptureReviewContract {
    DataSource source; JdbcTemplate jdbc; SqlSessionTemplate session; ContentCaptureReviewService service;
    TransactionTemplate tx; ContentCaptureSnapshot snapshot;
    void initialize(DataSource dataSource) throws Exception {
        source=dataSource; jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE cms_topic(id BIGINT PRIMARY KEY, del_flag INT)");
        jdbc.execute("CREATE TABLE product_role(id BIGINT PRIMARY KEY,topic_id BIGINT,slot VARCHAR(30),entity_type VARCHAR(30),entity_id BIGINT,status INT)");
        jdbc.execute("CREATE TABLE audit_task(id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_type VARCHAR(64) NOT NULL,biz_id BIGINT NOT NULL,submitter_id BIGINT,source_type VARCHAR(64),risk_level VARCHAR(30),status TINYINT NOT NULL,current_assignee BIGINT,submitted_at TIMESTAMP,deadline_at TIMESTAMP,snapshot_json LONGTEXT,version INT,create_by VARCHAR(64),create_time TIMESTAMP,update_by VARCHAR(64),update_time TIMESTAMP,remark VARCHAR(500))");
        boolean mysql;
        try(java.sql.Connection connection=source.getConnection()){mysql="MySQL".equals(connection.getMetaData().getDatabaseProductName());}
        jdbc.execute("ALTER TABLE audit_task ADD COLUMN open_guard TINYINT "
                +(mysql ? "GENERATED ALWAYS AS (CASE WHEN status IN (0,3) THEN 1 ELSE NULL END) STORED"
                        : "AS (CASE WHEN status IN (0,3) THEN 1 ELSE NULL END)"));
        jdbc.execute("ALTER TABLE audit_task ADD UNIQUE KEY uk_audit_biz_open (biz_type,biz_id,open_guard)");
        jdbc.execute("CREATE TABLE audit_record(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,action VARCHAR(30),operator_id BIGINT,reason_code VARCHAR(64),reason_text VARCHAR(500),snapshot_json LONGTEXT,reverted_record_id BIGINT,create_time TIMESTAMP)");
        jdbc.update("INSERT INTO cms_topic VALUES(10,0)");
        jdbc.update("INSERT INTO product_role VALUES(1,10,'CONTENT','MEMBER',7,1)");
        Configuration c=new Configuration(new Environment("review",new SpringManagedTransactionFactory(),source));
        c.getTypeAliasRegistry().registerAlias("AuditTask",AuditTask.class);c.getTypeAliasRegistry().registerAlias("AuditRecord",AuditRecord.class);
        for(String name:new String[]{"ContentCaptureSnapshotMapper","ContentCaptureReviewMapper","AuditTaskMapper","AuditRecordMapper"}) {
            String resource="mapper/business/"+name+".xml";
            try(InputStream in=getClass().getClassLoader().getResourceAsStream(resource)){assertNotNull(in);new XMLMapperBuilder(in,c,resource,c.getSqlFragments()).parse();}
        }
        session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));
        service=new ContentCaptureReviewService(session.getMapper(ContentCaptureReviewMapper.class),session.getMapper(AuditTaskMapper.class),session.getMapper(AuditRecordMapper.class));
        ReflectionTestUtils.setField(service,"enabled",true);
        // H2 has no MySQL trigger/metadata equivalent; only hosted MySQL exercises the real preflight.
        ReflectionTestUtils.setField(service,"schemaPreflight",mysql ? new ContentCaptureReviewSchemaPreflight(source)
                : org.mockito.Mockito.mock(ContentCaptureReviewSchemaPreflight.class));
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        // Minimal canonical candidate envelope, no fake claim of complete or published content.
        String json="{\"completeRelease\":false,\"contract\":\"questify.content-capture-candidate.v1\",\"draftConfigVersion\":4,\"schemaVersion\":1,\"secretAnswer\":\"server-only-answer\",\"topicId\":10}";
        snapshot=new ContentCaptureSnapshot();snapshot.setTopicId(10L);snapshot.setCapturedByMemberId(7L);snapshot.setRequestId("capture");
        snapshot.setSourceConfigVersion(4L);snapshot.setContentHash(CanonicalContentJson.sha256(json));snapshot.setCanonicalJson(json);
        run(()->session.getMapper(ContentCaptureSnapshotMapper.class).insertFrozen(snapshot));
    }
    <T>T run(Supplier<T> action){return tx.execute(status->action.get());}
    long submit(String request){return run(()->service.submit(10,7,snapshot.getId(),4,snapshot.getContentHash(),request));}
    AuditReviewDTO request(int version){AuditReviewDTO r=new AuditReviewDTO();r.setExpectedVersion(version);r.setStatus(1);return r;}
    void approve(long taskId) {
        run(()->{AuditTask task=service.lockAndValidateReview(taskId,90,request(0));
            assertEquals(1,session.getMapper(AuditTaskMapper.class).updateAuditTaskReviewCas(taskId,0,1,90L,0,new Date()));
            AuditRecord record=new AuditRecord();record.setTaskId(taskId);record.setAction("APPROVE");record.setOperatorId(90L);
            record.setSnapshotJson(task.getSnapshotJson());record.setCreateTime(new Date());
            assertEquals(1,session.getMapper(AuditRecordMapper.class).insertAuditRecord(record));return null;});
    }
    @Test void submissionPersistsExactIdentityAndReplayUsesSameTask() {
        long task=submit("submit-1");assertEquals(task,submit("submit-1"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_review_binding",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
        assertFalse(run(()->service.inspect(task)).candidateReviewed());
    }
    @Test void secondCandidateCannotBypassExistingOneOpenTaskPerTopicGuard() {
        long first=submit("first-open");
        snapshot.setRequestId("capture-second");snapshot.setId(null);
        run(()->session.getMapper(ContentCaptureSnapshotMapper.class).insertFrozen(snapshot));
        assertThrows(org.springframework.dao.DataAccessException.class,()->submit("second-open"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_review_binding",Integer.class));
        approve(first);
        long second=submit("second-open");assertNotEquals(first,second);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_review_binding",Integer.class));
    }
    @Test void hashVersionOrRequestReuseMismatchFailsWithoutExtraRows() {
        submit("submit-1");
        assertThrows(IllegalStateException.class,()->run(()->service.submit(10,7,snapshot.getId(),5,snapshot.getContentHash(),"submit-1")));
        assertThrows(IllegalStateException.class,()->run(()->service.submit(10,7,snapshot.getId(),4,String.format("%064d",0),"submit-1")));
        assertThrows(IllegalStateException.class,()->submit("different-request"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
    }
    @Test void ownerChangeBlocksSubmissionReplayReviewAndInspection() {
        long task=submit("owner");jdbc.update("UPDATE product_role SET entity_id=8");
        assertThrows(IllegalStateException.class,()->submit("owner"));
        assertThrows(IllegalStateException.class,()->run(()->service.lockAndValidateReview(task,90,request(0))));
        assertThrows(IllegalStateException.class,()->run(()->service.inspect(task)));
    }
    @Test void absentAmbiguousClubOrDeletedAuthorityFailsClosed() {
        jdbc.update("UPDATE product_role SET entity_type='CLUB'");assertThrows(IllegalStateException.class,()->submit("club"));
        jdbc.update("UPDATE product_role SET entity_type='MEMBER',status=0");assertThrows(IllegalStateException.class,()->submit("missing"));
        jdbc.update("UPDATE product_role SET status=1");jdbc.update("INSERT INTO product_role VALUES(2,10,'CONTENT','MEMBER',7,1)");assertThrows(IllegalStateException.class,()->submit("duplicate"));
        jdbc.update("DELETE FROM product_role WHERE id=2");jdbc.update("UPDATE cms_topic SET del_flag=2");assertThrows(IllegalStateException.class,()->submit("deleted"));
    }
    @Test void explicitTransactionRollbackRemovesBindingTaskAndSubmissionRecord() {
        tx.execute(status->{service.submit(10,7,snapshot.getId(),4,snapshot.getContentHash(),"rollback");status.setRollbackOnly();return null;});
        for(String table:new String[]{"content_capture_review_binding","audit_task","audit_record"})assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_snapshot",Integer.class));
    }
    @Test void submissionTimelineWriteFailureRollsBackTaskAndBinding() {
        jdbc.execute("ALTER TABLE audit_record ADD CONSTRAINT deny_submit CHECK (action <> 'SUBMIT')");
        assertThrows(org.springframework.dao.DataAccessException.class,()->submit("failed-record"));
        for(String table:new String[]{"content_capture_review_binding","audit_task","audit_record"})assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
    }
    @Test void offOrNonTransactionalInvocationWritesNothing() {
        assertThrows(IllegalStateException.class,()->service.submit(10,7,snapshot.getId(),4,snapshot.getContentHash(),"ambient"));
        ReflectionTestUtils.setField(service,"enabled",false);assertThrows(IllegalStateException.class,()->submit("off"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
    }
    @Test void taskEnvelopeTamperAndOldReviewTypeCannotBorrowBinding() {
        long task=submit("tamper");jdbc.update("UPDATE audit_task SET snapshot_json='{}' WHERE id=?",task);
        assertThrows(IllegalStateException.class,()->run(()->service.lockAndValidateReview(task,90,request(0))));
        jdbc.update("UPDATE audit_task SET biz_type='TOPIC' WHERE id=?",task);
        assertThrows(IllegalStateException.class,()->run(()->service.inspect(task)));
    }
    @Test void reviewerMustSupplyExactTaskVersionAndServerPrincipal() {
        long task=submit("version");
        assertThrows(IllegalStateException.class,()->run(()->service.lockAndValidateReview(task,90,request(1))));
        assertThrows(IllegalStateException.class,()->run(()->service.lockAndValidateReview(task,0,request(0))));
        AuditReviewDTO absent=request(0);absent.setExpectedVersion(null);
        assertThrows(IllegalStateException.class,()->run(()->service.lockAndValidateReview(task,90,absent)));
        assertEquals(task,run(()->service.lockAndValidateReview(task,90,request(0))).getId());
    }
    @Test void approvalEvidenceIsBoundButNeverPublicationOrRunAuthority() {
        long task=submit("approval");approve(task);
        ContentCaptureReviewService.ReviewEvidence evidence=run(()->service.inspect(task));
        assertTrue(evidence.candidateReviewed());assertEquals(1,evidence.auditTaskVersion());assertEquals(snapshot.getContentHash(),evidence.contentHash());
        assertFalse(evidence.publicationEligible());assertThrows(IllegalStateException.class,evidence::requirePublicationEligible);
    }
    @Test void taskStatusAloneDoesNotProveRecordedReview() {
        long task=submit("flag");jdbc.update("UPDATE audit_task SET status=1,current_assignee=90,version=1 WHERE id=?",task);
        assertThrows(IllegalStateException.class,()->run(()->service.inspect(task)));
    }
    @Test void revertedDecisionNoLongerCountsAsReviewed() {
        long task=submit("revert");approve(task);
        jdbc.update("UPDATE audit_task SET status=0,current_assignee=NULL,version=2 WHERE id=?",task);
        assertFalse(run(()->service.inspect(task)).candidateReviewed());
        assertThrows(IllegalStateException.class,()->run(()->service.lockAndValidateReview(task,90,request(0))));
    }
    @Test void reviewEnvelopeAndEvidenceDoNotSerializeSecretAnswers() throws Exception {
        long task=submit("privacy");String envelope=jdbc.queryForObject("SELECT snapshot_json FROM audit_task WHERE id=?",String.class,task);
        assertFalse(envelope.contains("server-only-answer"));assertTrue(envelope.contains(snapshot.getContentHash()));
        ObjectMapper mapper=new ObjectMapper().disable(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS);
        assertFalse(mapper.writeValueAsString(run(()->service.inspect(task))).contains("server-only-answer"));
    }
}
