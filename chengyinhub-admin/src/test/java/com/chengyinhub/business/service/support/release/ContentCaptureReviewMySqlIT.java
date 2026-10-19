package com.chengyinhub.business.service.support.release;
import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;
/** Actual additive migration and inherited mapper contracts on disposable loopback Oracle MySQL. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
class ContentCaptureReviewMySqlIT extends ContentCaptureReviewContract {
    // Explicit overrides let the existing gate count every inherited contract without changing its parser.
    @Override
    @Test void submissionPersistsExactIdentityAndReplayUsesSameTask() { super.submissionPersistsExactIdentityAndReplayUsesSameTask(); }
    @Override
    @Test void secondCandidateCannotBypassExistingOneOpenTaskPerTopicGuard() { super.secondCandidateCannotBypassExistingOneOpenTaskPerTopicGuard(); }
    @Override
    @Test void hashVersionOrRequestReuseMismatchFailsWithoutExtraRows() { super.hashVersionOrRequestReuseMismatchFailsWithoutExtraRows(); }
    @Override
    @Test void ownerChangeBlocksSubmissionReplayReviewAndInspection() { super.ownerChangeBlocksSubmissionReplayReviewAndInspection(); }
    @Override
    @Test void absentAmbiguousClubOrDeletedAuthorityFailsClosed() { super.absentAmbiguousClubOrDeletedAuthorityFailsClosed(); }
    @Override
    @Test void explicitTransactionRollbackRemovesBindingTaskAndSubmissionRecord() { super.explicitTransactionRollbackRemovesBindingTaskAndSubmissionRecord(); }
    @Override
    @Test void submissionTimelineWriteFailureRollsBackTaskAndBinding() { super.submissionTimelineWriteFailureRollsBackTaskAndBinding(); }
    @Override
    @Test void offOrNonTransactionalInvocationWritesNothing() { super.offOrNonTransactionalInvocationWritesNothing(); }
    @Override
    @Test void taskEnvelopeTamperAndOldReviewTypeCannotBorrowBinding() { super.taskEnvelopeTamperAndOldReviewTypeCannotBorrowBinding(); }
    @Override
    @Test void reviewerMustSupplyExactTaskVersionAndServerPrincipal() { super.reviewerMustSupplyExactTaskVersionAndServerPrincipal(); }
    @Override
    @Test void approvalEvidenceIsBoundButNeverPublicationOrRunAuthority() { super.approvalEvidenceIsBoundButNeverPublicationOrRunAuthority(); }
    @Override
    @Test void taskStatusAloneDoesNotProveRecordedReview() { super.taskStatusAloneDoesNotProveRecordedReview(); }
    @Override
    @Test void revertedDecisionNoLongerCountsAsReviewed() { super.revertedDecisionNoLongerCountsAsReviewed(); }
    @Override
    @Test void reviewEnvelopeAndEvidenceDoNotSerializeSecretAnswers() throws Exception { super.reviewEnvelopeAndEvidenceDoNotSerializeSecretAnswers(); }
    private IsolatedMySqlSchema schema;
    @BeforeEach void setup() throws Exception {
        schema=new IsolatedMySqlSchema();source=schema.open("capture_review");
        schema.apply("20261002_content_capture_snapshot");schema.apply("20261003_content_capture_review_binding");initialize(source);
    }
    @AfterEach void cleanup() throws Exception {if(schema!=null)schema.close();}
    @Test void bindingUpdateAndDeleteTriggersRejectIdentityChanges() {
        submit("frozen");
        for(String sql:new String[]{"UPDATE content_capture_review_binding SET content_hash=REPEAT('a',64)","UPDATE content_capture_review_binding SET audit_task_id=99","DELETE FROM content_capture_review_binding"})
            assertThrows(DataAccessException.class,()->jdbc.update(sql));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_review_binding",Integer.class));
    }
    @Test void concurrentSameSubmissionReturnsOneTask() throws Exception {
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(2),start=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Long> call=()->{ready.countDown();assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));return submit("race");};
        try {
            java.util.concurrent.Future<Long> a=pool.submit(call),b=pool.submit(call);
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            assertEquals(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_review_binding",Integer.class));
        } finally {start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void concurrentDifferentRequestsCannotDuplicateCandidateReview() throws Exception {
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> first=()->{assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));try{submit("race-a");return true;}catch(IllegalStateException conflict){return false;}};
        java.util.concurrent.Callable<Boolean> second=()->{assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));try{submit("race-b");return true;}catch(IllegalStateException conflict){return false;}};
        try {
            java.util.concurrent.Future<Boolean> a=pool.submit(first),b=pool.submit(second);start.countDown();
            assertNotEquals(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_review_binding",Integer.class));
        } finally {start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void ownerTransferCommittedWhileReviewWaitsIsObserved() throws Exception {
        long task=submit("owner-race");
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(java.sql.Connection connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try(java.sql.Statement statement=connection.createStatement()) {
                statement.executeQuery("SELECT id FROM cms_topic WHERE id=10 FOR UPDATE").close();
                statement.executeUpdate("UPDATE product_role SET entity_id=8 WHERE id=1");
                java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Future<Boolean> review=pool.submit(()->{started.countDown();
                    assertThrows(IllegalStateException.class,()->run(()->service.lockAndValidateReview(task,90,request(0))));return true;});
                assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS));connection.commit();
                assertTrue(review.get(20,java.util.concurrent.TimeUnit.SECONDS));
            } finally {connection.rollback();}
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void strictMigrationRejectsRepeatAndWeakPreexistingTable() throws Exception {
        assertThrows(java.sql.SQLException.class,()->schema.apply("20261003_content_capture_review_binding"));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='content_capture_review_binding' AND non_unique=0 AND index_name<>'PRIMARY'",Integer.class));
        jdbc.execute("DROP TABLE content_capture_review_binding");
        jdbc.execute("CREATE TABLE content_capture_review_binding(id BIGINT)");
        assertThrows(java.sql.SQLException.class,()->schema.apply("20261003_content_capture_review_binding"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_capture_review_binding'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table='content_capture_review_binding'",Integer.class));
    }
    @Test void actualBindingRequestColumnUsesBinaryAsciiCollation() {
        assertEquals("ascii_bin",jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_capture_review_binding' AND column_name='request_id'",String.class));
        assertEquals("InnoDB",jdbc.queryForObject("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='content_capture_review_binding'",String.class));
    }

    /** New transport probes retain all original 20 binding contracts above. */
    private com.fasterxml.jackson.databind.node.ObjectNode httpBody(String key) {
        com.fasterxml.jackson.databind.node.ObjectNode b=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        b.put("topicId",10);b.put("snapshotId",snapshot.getId());b.put("expectedVersion",4);
        b.put("expectedHash",snapshot.getContentHash());b.put("requestId",key);return b;
    }
    private com.fasterxml.jackson.databind.JsonNode http(long member,String action,
            com.fasterxml.jackson.databind.node.ObjectNode body,int status)throws Exception {
        // Recreate controller/proxy for every request: receipts cannot rely on in-memory transport state.
        org.springframework.aop.framework.ProxyFactory proxy=new org.springframework.aop.framework.ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.chengyinhub.web.controller.api.ApiContentCaptureReviewController((ContentCaptureReviewService)proxy.getProxy())).build();
        com.chengyinhub.common.core.domain.entity.AppUser user=new com.chengyinhub.common.core.domain.entity.AppUser();user.setUserId(member);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.chengyinhub.common.core.domain.model.LoginAppUser(member,user),null,java.util.Collections.emptyList()));
        try {
            String response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/content-capture-review/"+action)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body.toString()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(status))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("server-only-answer"));
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void transportDurableSubmissionReplayAndReadStatusUseSameBoundTask()throws Exception {
        com.fasterxml.jackson.databind.JsonNode first=http(7,"submit",httpBody("http-durable"),200).path("data");
        assertEquals(first,http(7,"submit",httpBody("http-durable"),200).path("data"));
        assertEquals(first,http(7,"status",httpBody("http-durable"),200).path("data"));
        assertEquals("PENDING",first.path("status").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
    }
    @Test void transportExactVersionHashKeyAndCandidateConflictsDoNotWrite()throws Exception {
        http(7,"submit",httpBody("http-exact"),200);
        for(String action:new String[]{"submit","status"}) {
            http(7,action,httpBody("http-exact").put("expectedVersion",5),409);
            http(7,action,httpBody("http-exact").put("expectedHash",String.format("%064d",0)),409);
            http(7,action,httpBody("http-exact").put("snapshotId",snapshot.getId()+1),409);
            http(7,action,httpBody("different-key"),409);
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
    }
    @Test void transportCurrentMemberContentOwnerIsRequiredForSubmitReplayAndStatus()throws Exception {
        http(7,"submit",httpBody("http-owner"),200);
        for(String action:new String[]{"submit","status"})http(8,action,httpBody("http-owner"),409);
        jdbc.update("UPDATE product_role SET entity_type='CLUB'");
        for(String action:new String[]{"submit","status"})http(7,action,httpBody("http-owner"),409);
        jdbc.update("UPDATE product_role SET entity_type='MEMBER',entity_id=8");
        for(String action:new String[]{"submit","status"})http(7,action,httpBody("http-owner"),409);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
    }
    @Test void transportTerminalApprovalReplayIsObservationOnly()throws Exception {
        long task=http(7,"submit",httpBody("http-approved"),200).path("data").path("auditTaskId").asLong();approve(task);
        com.fasterxml.jackson.databind.JsonNode state=http(7,"submit",httpBody("http-approved"),200).path("data");
        assertEquals("APPROVED",state.path("status").asText());assertTrue(state.path("candidateReviewed").asBoolean());
        assertFalse(state.path("completeRelease").asBoolean());assertFalse(state.path("publicationEligible").asBoolean());
        assertEquals(state,http(7,"status",httpBody("http-approved"),200).path("data"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task WHERE biz_type='TOPIC'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
    }
    @Test void transportConcurrentSameKeyReturnsOneDurableTask()throws Exception {
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Long> call=()->{assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));
            return http(7,"submit",httpBody("http-race"),200).path("data").path("auditTaskId").asLong();};
        try {
            java.util.concurrent.Future<Long> a=pool.submit(call),b=pool.submit(call);start.countDown();
            assertEquals(a.get(30,java.util.concurrent.TimeUnit.SECONDS),b.get(30,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
        } finally {start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void transportExistingOpenReviewCollisionRollsBackBeforeExplicitRetry()throws Exception {
        long first=http(7,"submit",httpBody("http-first"),200).path("data").path("auditTaskId").asLong();
        snapshot.setId(null);snapshot.setRequestId("http-next-capture");
        run(()->session.getMapper(com.chengyinhub.business.mapper.ContentCaptureSnapshotMapper.class).insertFrozen(snapshot));
        http(7,"submit",httpBody("http-second"),409);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_review_binding",Integer.class));
        approve(first);http(7,"submit",httpBody("http-second"),200);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
    }
    @Test void transportAppliedSchemaDriftBlocksSubmitAndStatusWithoutSensitiveError()throws Exception {
        http(7,"submit",httpBody("http-drift"),200);
        jdbc.execute("ALTER TABLE content_capture_review_binding DROP INDEX uk_capture_review_snapshot");
        for(String action:new String[]{"submit","status"}) {
            com.fasterxml.jackson.databind.JsonNode response=http(7,action,httpBody("http-drift"),503);
            assertEquals("CONTENT_REVIEW_UNAVAILABLE",response.path("errorCode").asText());
            assertFalse(response.toString().contains("uk_capture_review_snapshot"));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
    }
}
