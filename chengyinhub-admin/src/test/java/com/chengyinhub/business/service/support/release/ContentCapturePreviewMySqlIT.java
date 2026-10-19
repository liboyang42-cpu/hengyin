package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.business.service.impl.TopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.web.controller.business.ContentCapturePreviewController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.io.InputStream;
import java.sql.*;
import java.util.Collections;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exact production controller, mapper and schema preflight on disposable Oracle MySQL 8.0.45.
 * No H2 fallback. Delegated scope is mocked ONLY to reach the otherwise superadmin-only grant branch.
 * Lock tests require actual 1205 results; old-RR tests establish a snapshot before committed revocation. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
class ContentCapturePreviewMySqlIT {
    private final ContentCaptureReviewContract fixture=new ContentCaptureReviewContract() { };
    private IsolatedMySqlSchema schema;
    private ContentCapturePreviewService previews;
    private MockMvc mvc;
    private long task;

    @BeforeEach void setup() throws Exception {
        schema=new IsolatedMySqlSchema();
        javax.sql.DataSource source=schema.open("capture_preview");
        schema.apply("20261002_content_capture_snapshot");
        schema.apply("20261003_content_capture_review_binding");
        fixture.initialize(source);
        fixture.jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,status CHAR(1),del_flag CHAR(1)) ENGINE=InnoDB");
        fixture.jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,status CHAR(1),del_flag CHAR(1)) ENGINE=InnoDB");
        fixture.jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT,PRIMARY KEY(user_id,role_id)) ENGINE=InnoDB");
        fixture.jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id)) ENGINE=InnoDB");
        fixture.jdbc.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,status CHAR(1),perms VARCHAR(100)) ENGINE=InnoDB");
        fixture.jdbc.update("INSERT INTO sys_user VALUES(1,'0','0'),(90,'0','0')");
        fixture.jdbc.update("INSERT INTO sys_role VALUES(2,'0','0')");
        fixture.jdbc.update("INSERT INTO sys_user_role VALUES(90,2)");
        fixture.jdbc.update("INSERT INTO sys_role_menu VALUES(2,3)");
        fixture.jdbc.update("INSERT INTO sys_menu VALUES(3,'0','business:audit:review')");
        String resource="mapper/business/ContentCapturePreviewPermissionMapper.xml";
        try(InputStream in=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in);
            new XMLMapperBuilder(in,fixture.session.getConfiguration(),resource,fixture.session.getConfiguration().getSqlFragments()).parse();
        }
        ContentCaptureRows rows=ContentCaptureBuilderTest.rows(); rows.topic.setConfigVersion(4L);
        rows.nodes.get(0).setName("Frozen node"); rows.nodes.get(0).setDescription("Captured <b>narrative</b>");
        ObjectNode root=(ObjectNode)CanonicalContentJson.JSON.readTree(new ContentCaptureBuilder().build(rows).serverOnlyJson());
        root.put("privateAnswer","NEVER_EXPOSE_ANSWER");root.putObject("npcInternalKnowledge").put("apiKey","NEVER_EXPOSE_CREDENTIAL");
        fixture.snapshot.setId(null);fixture.snapshot.setRequestId("preview-capture");
        fixture.snapshot.setCanonicalJson(CanonicalContentJson.write(root));
        fixture.snapshot.setContentHash(CanonicalContentJson.sha256(fixture.snapshot.getCanonicalJson()));
        fixture.run(()->fixture.session.getMapper(ContentCaptureSnapshotMapper.class).insertFrozen(fixture.snapshot));
        task=fixture.submit("preview");
        previews=new ContentCapturePreviewService(fixture.service,fixture.session.getMapper(AuditTaskMapper.class),
                fixture.session.getMapper(ContentCapturePreviewPermissionMapper.class),new TopicOperationsScopeAuthorizer());
        ProxyFactory proxy=new ProxyFactory(previews);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        mvc=MockMvcBuilders.standaloneSetup(new ContentCapturePreviewController((ContentCapturePreviewService)proxy.getProxy())).build();
        reviewer(1);
    }
    @AfterEach void cleanup() throws Exception {
        SecurityContextHolder.clearContext(); if(schema!=null)schema.close();
    }
    private void reviewer(long id) {
        SysUser user=new SysUser();user.setUserId(id);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(id,1L,user,Collections.singleton("business:audit:review")),null,Collections.emptyList()));
    }
    private void delegatedScopeForGrantTestsOnly() {
        ITopicOperationsScopeAuthorizer scope=mock(ITopicOperationsScopeAuthorizer.class);
        when(scope.canViewTopic(10L)).thenReturn(true);
        ReflectionTestUtils.setField(previews,"scope",scope);
    }
    private MockHttpServletRequestBuilder request() {
        return get("/business/audit/"+task+"/candidate-preview").param("expectedTaskVersion","0")
                .param("snapshotId",fixture.snapshot.getId().toString()).param("sourceConfigVersion","4")
                .param("contentHash",fixture.snapshot.getContentHash());
    }
    private JsonNode preview(int expected) { return preview(request(),expected); }
    private JsonNode preview(MockHttpServletRequestBuilder request,int expected) {
        try {
            String response=mvc.perform(request).andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(expected))
                    .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("NEVER_EXPOSE")); assertFalse(response.contains("canonicalJson"));
            assertFalse(response.contains("snapshotJson")); assertFalse(response.contains("jdbc:"));
            return CanonicalContentJson.JSON.readTree(response);
        } catch(Exception error) { throw new AssertionError(error); }
    }
    private void assertReadOnly() {
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT status FROM audit_task WHERE id=?",Integer.class,task));
    }
    @Test void exactFrozenTextRemainsInertPartialAndIndependentOfMutableSources() {
        JsonNode result=preview(200).path("data");
        assertTrue(result.toString().contains("Frozen node"));assertTrue(result.toString().contains("&lt;b&gt;narrative&lt;/b&gt;"));
        assertFalse(result.path("previewComplete").asBoolean());assertFalse(result.path("completeRelease").asBoolean());
        assertFalse(result.path("publicationEligible").asBoolean());assertEquals("INCOMPLETE_CANDIDATE_TEXT_ONLY",result.path("reviewScope").asText());
        assertTrue(result.path("snapshotId").isTextual());assertEquals(fixture.snapshot.getContentHash(),result.path("contentHash").asText());
        fixture.jdbc.execute("ALTER TABLE cms_topic ADD name VARCHAR(100)");fixture.jdbc.update("UPDATE cms_topic SET name='New live source'");
        assertEquals(result,preview(200).path("data"));assertReadOnly();
    }
    @Test void platformRealmAndCurrentSuperadminTopicScopeAreRequired() {
        reviewer(90);preview(403);
        fixture.jdbc.update("UPDATE audit_task SET current_assignee=90");preview(403);
        preview(get("/business/audit/999999/candidate-preview").param("expectedTaskVersion","0")
                .param("snapshotId",fixture.snapshot.getId().toString()).param("sourceConfigVersion","4")
                .param("contentHash",fixture.snapshot.getContentHash()),403);
        AppUser user=new AppUser();user.setUserId(1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginAppUser(1L,user),null,Collections.emptyList()));
        preview(403);SecurityContextHolder.clearContext();preview(403);assertReadOnly();
    }
    @Test void exactTaskMetadataConflictsButSchemaDriftIsUnavailable() {
        fixture.jdbc.update("UPDATE audit_task SET version=1");preview(409);
        fixture.jdbc.update("UPDATE audit_task SET version=0,snapshot_json='{}'");preview(409);
        fixture.jdbc.execute("ALTER TABLE content_capture_review_binding DROP INDEX uk_capture_review_snapshot");
        assertEquals("CONTENT_PREVIEW_UNAVAILABLE",preview(503).path("errorCode").asText());assertReadOnly();
    }
    @Test void everyCurrentGrantRevocationAndAssignmentIsRechecked() {
        delegatedScopeForGrantTestsOnly();reviewer(90);preview(200);
        fixture.jdbc.update("UPDATE sys_role SET status='1'");preview(403);fixture.jdbc.update("UPDATE sys_role SET status='0',del_flag='2'");preview(403);
        fixture.jdbc.update("UPDATE sys_role SET del_flag='0'");fixture.jdbc.update("UPDATE sys_menu SET status='1'");preview(403);
        fixture.jdbc.update("UPDATE sys_menu SET status='0',perms='business:audit:list'");preview(403);
        fixture.jdbc.update("UPDATE sys_menu SET perms='business:audit:review'");fixture.jdbc.update("DELETE FROM sys_role_menu");preview(403);
        fixture.jdbc.update("INSERT INTO sys_role_menu VALUES(2,3)");fixture.jdbc.update("DELETE FROM sys_user_role");preview(403);
        fixture.jdbc.update("INSERT INTO sys_user_role VALUES(90,2)");fixture.jdbc.update("UPDATE audit_task SET current_assignee=91");preview(403);
        fixture.jdbc.update("UPDATE audit_task SET current_assignee=90");preview(200);assertReadOnly();
    }
    @Test void immutableSnapshotAndBindingTriggersProtectTheDisplayedBytes() {
        for(String sql:new String[]{"UPDATE content_capture_snapshot SET canonical_json='{}'", "DELETE FROM content_capture_snapshot",
                "UPDATE content_capture_review_binding SET content_hash=REPEAT('a',64)","DELETE FROM content_capture_review_binding"})
            assertThrows(org.springframework.dao.DataAccessException.class,()->fixture.jdbc.update(sql));
        preview(200);assertReadOnly();
    }
    @Test void accountRowRemainsLockedThroughThePreviewTransaction() {
        holdsAgainst(1,"UPDATE sys_user SET status='1' WHERE user_id=1");
    }
    @Test void joinedPermissionQueryLocksEveryGrantingRelation() {
        delegatedScopeForGrantTestsOnly();
        for(String sql:new String[]{"DELETE FROM sys_user_role WHERE user_id=90", "UPDATE sys_role SET status='1' WHERE role_id=2",
                "DELETE FROM sys_role_menu WHERE role_id=2", "UPDATE sys_menu SET perms='business:audit:list' WHERE menu_id=3"}) holdsAgainst(90,sql);
    }
    @Test void currentTopicAndOwnerRowsRemainLockedThroughPreview() {
        holdsAgainst(1,"UPDATE cms_topic SET del_flag=2 WHERE id=10");
        holdsAgainst(1,"UPDATE product_role SET entity_id=8 WHERE id=1");
    }
    @Test void taskAssignmentAndVersionRemainLockedThroughPreview() {
        holdsAgainst(1,"UPDATE audit_task SET current_assignee=91,version=version+1 WHERE id="+task);
    }
    private void holdsAgainst(long reviewer,String sql) {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            reviewer(reviewer);
            fixture.run(()->{
                preview(200);
                Future<Integer> error=pool.submit(()->{
                    try(Connection connection=fixture.source.getConnection();Statement update=connection.createStatement()) {
                        update.execute("SET SESSION innodb_lock_wait_timeout=1");
                        try {update.executeUpdate(sql);return 0;} catch(SQLException failure) {return failure.getErrorCode();}
                    }
                });
                try {assertEquals(1205,error.get(5,TimeUnit.SECONDS));} catch(Exception failure){throw new AssertionError(failure);}
                return null;
            });
            preview(200);assertReadOnly();
        } finally {shutdown(pool);}
    }
    @Test void oldRepeatableReadSnapshotCannotReviveDisabledAccount() throws Exception {
        afterSnapshot(1,"UPDATE sys_user SET status='1' WHERE user_id=1",403);
    }
    @Test void oldRepeatableReadSnapshotCannotReviveDisabledRole() throws Exception {
        delegatedScopeForGrantTestsOnly();afterSnapshot(90,"UPDATE sys_role SET status='1' WHERE role_id=2",403);
    }
    @Test void oldRepeatableReadSnapshotCannotReviveDeletedMembership() throws Exception {
        delegatedScopeForGrantTestsOnly();afterSnapshot(90,"DELETE FROM sys_user_role WHERE user_id=90",403);
    }
    @Test void oldRepeatableReadSnapshotCannotReviveDeletedRoleMenuGrant() throws Exception {
        delegatedScopeForGrantTestsOnly();afterSnapshot(90,"DELETE FROM sys_role_menu WHERE role_id=2",403);
    }
    @Test void oldRepeatableReadSnapshotCannotReviveDisabledMenu() throws Exception {
        delegatedScopeForGrantTestsOnly();afterSnapshot(90,"UPDATE sys_menu SET status='1' WHERE menu_id=3",403);
    }
    @Test void oldRepeatableReadSnapshotCannotReviveTransferredContentOwner() throws Exception {
        afterSnapshot(1,"UPDATE product_role SET entity_id=8 WHERE id=1",409);
    }
    @Test void lockedAssignmentWinsOverAnOldRepeatableReadTaskHint() throws Exception {
        delegatedScopeForGrantTestsOnly();afterSnapshot(90,"UPDATE audit_task SET current_assignee=91 WHERE id="+task,403);
    }
    @Test void lockedVersionWinsOverAnOldRepeatableReadTaskHint() throws Exception {
        afterSnapshot(1,"UPDATE audit_task SET version=1 WHERE id="+task,409);
    }
    private void afterSnapshot(long reviewer,String mutation,int expected) throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        CountDownLatch read=new CountDownLatch(1),committed=new CountDownLatch(1);
        try {
            Future<JsonNode> result=pool.submit(()->{
                reviewer(reviewer);
                try {return fixture.tx.execute(status->{
                    assertEquals("REPEATABLE-READ",fixture.jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
                    assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
                    read.countDown();
                    try {assertTrue(committed.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
                    JsonNode response=preview(expected);status.setRollbackOnly();return response;
                });} finally {SecurityContextHolder.clearContext();}
            });
            assertTrue(read.await(10,TimeUnit.SECONDS));assertEquals(1,fixture.jdbc.update(mutation));committed.countDown();
            assertEquals(expected,result.get(15,TimeUnit.SECONDS).path("code").asInt());assertReadOnly();
        } finally {committed.countDown();shutdown(pool);}
    }
    @Test void actualDatabaseLockTimeoutIsUnavailableNotCandidateConflict() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try(Connection owner=fixture.source.getConnection();Statement lock=owner.createStatement()) {
            owner.setAutoCommit(false);lock.executeQuery("SELECT user_id FROM sys_user WHERE user_id=1 FOR UPDATE").close();
            try {
                Future<JsonNode> result=pool.submit(()->{
                    reviewer(1);
                    try {return fixture.tx.execute(status->{
                        fixture.jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                        JsonNode response=preview(503);status.setRollbackOnly();return response;
                    });} finally {SecurityContextHolder.clearContext();}
                });
                assertEquals("CONTENT_PREVIEW_UNAVAILABLE",result.get(5,TimeUnit.SECONDS).path("errorCode").asText());
            } finally {owner.rollback();}
        } finally {shutdown(pool);}
        preview(200);assertReadOnly();
    }
    private void shutdown(ExecutorService pool) {
        pool.shutdownNow();try {assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
}
