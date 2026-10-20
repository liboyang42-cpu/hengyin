package com.chengyinhub.business.service.support.release;
import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Live already-applied schema mutations. Does not re-run DDL or rely on the migration ledger. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
class ContentCaptureReviewSchemaMySqlIT {
    private IsolatedMySqlSchema schema;private DataSource source;private JdbcTemplate jdbc;
    private ContentCaptureReviewContract fixture;private ContentCaptureReviewSchemaPreflight guard;
    @BeforeEach void setup() throws Exception {
        schema=new IsolatedMySqlSchema();source=schema.open("reviewschema");
        schema.apply("20261002_content_capture_snapshot");schema.apply("20261003_content_capture_review_binding");
        fixture=new ContentCaptureReviewContract(){};fixture.initialize(source);jdbc=fixture.jdbc;
        guard=new ContentCaptureReviewSchemaPreflight(source);accepted();
    }
    @AfterEach void cleanup() throws Exception {if(schema!=null)schema.close();}
    private void accepted(){fixture.run(()->{guard.verifyCurrentTransaction();return null;});}
    private void rejected(){assertThrows(IllegalStateException.class,this::accepted);}
    @Test void exactAppliedSchemaPassesRepeatedReadOnlyPreflight(){accepted();accepted();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_review_binding",Integer.class));}
    @Test void missingSnapshotUniqueIndexFails(){jdbc.execute("ALTER TABLE content_capture_review_binding DROP INDEX uk_capture_review_snapshot");rejected();}
    @Test void sameNameNonuniqueRequestIndexFails(){jdbc.execute("ALTER TABLE content_capture_review_binding DROP INDEX uk_capture_review_request, ADD KEY uk_capture_review_request(topic_id,member_id,request_id)");rejected();}
    @Test void changedTaskUniqueColumnsFail(){jdbc.execute("ALTER TABLE content_capture_review_binding DROP INDEX uk_capture_review_task, ADD UNIQUE KEY uk_capture_review_task(topic_id,audit_task_id)");rejected();}
    @Test void prefixedRequestUniqueIndexFails(){jdbc.execute("ALTER TABLE content_capture_review_binding DROP INDEX uk_capture_review_request, ADD UNIQUE KEY uk_capture_review_request(topic_id,member_id,request_id(16))");rejected();}
    @Test void caseInsensitiveHashCollationFails(){jdbc.execute("ALTER TABLE content_capture_review_binding MODIFY content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL");rejected();}
    @Test void caseInsensitiveRequestCollationFails(){jdbc.execute("ALTER TABLE content_capture_review_binding MODIFY request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL");rejected();}
    @Test void nullableMemberIdentityFails(){jdbc.execute("ALTER TABLE content_capture_review_binding MODIFY member_id BIGINT NULL");rejected();}
    @Test void shortenedRequestColumnFails(){jdbc.execute("ALTER TABLE content_capture_review_binding MODIFY request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");rejected();}
    @Test void invisibleBindingUniqueIndexFails(){jdbc.execute("ALTER TABLE content_capture_review_binding ALTER INDEX uk_capture_review_snapshot INVISIBLE");rejected();}
    @Test void nonTransactionalBindingEngineFails(){jdbc.execute("ALTER TABLE content_capture_review_binding ENGINE=MyISAM");rejected();}
    @Test void virtualAuditGuardFails(){jdbc.execute("ALTER TABLE audit_task DROP INDEX uk_audit_biz_open, DROP COLUMN open_guard");jdbc.execute("ALTER TABLE audit_task ADD COLUMN open_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status IN (0,3) THEN 1 ELSE NULL END) VIRTUAL, ADD UNIQUE KEY uk_audit_biz_open(biz_type,biz_id,open_guard)");rejected();}
    @Test void missingUpdateTriggerFails(){jdbc.execute("DROP TRIGGER content_capture_review_no_update");rejected();}
    @Test void sameNameNoopTriggerFails(){jdbc.execute("DROP TRIGGER content_capture_review_no_update");jdbc.execute("CREATE TRIGGER content_capture_review_no_update BEFORE UPDATE ON content_capture_review_binding FOR EACH ROW SET @capture_review_noop=1");rejected();}
    @Test void sameNameConditionalSignalTriggerFails(){jdbc.execute("DROP TRIGGER content_capture_review_no_delete");jdbc.execute("CREATE TRIGGER content_capture_review_no_delete BEFORE DELETE ON content_capture_review_binding FOR EACH ROW BEGIN IF 1=0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Candidate review bindings cannot be deleted'; END IF; END");rejected();}
    @Test void missingAuditOpenGuardFails(){jdbc.execute("ALTER TABLE audit_task DROP INDEX uk_audit_biz_open, DROP COLUMN open_guard");rejected();}
    @Test void auditGuardWithWrongStateSetFails(){jdbc.execute("ALTER TABLE audit_task DROP INDEX uk_audit_biz_open, DROP COLUMN open_guard");jdbc.execute("ALTER TABLE audit_task ADD COLUMN open_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status=0 THEN 1 ELSE NULL END) STORED, ADD UNIQUE KEY uk_audit_biz_open(biz_type,biz_id,open_guard)");rejected();}
    @Test void whitespaceQuotedLookalikeStatusColumnCannotImpersonateGuard() {
        jdbc.execute("ALTER TABLE audit_task DROP INDEX uk_audit_biz_open, DROP COLUMN open_guard, ADD COLUMN `sta tus` TINYINT NULL");
        jdbc.execute("ALTER TABLE audit_task ADD COLUMN open_guard TINYINT GENERATED ALWAYS AS (CASE WHEN `sta tus` IN (0,3) THEN 1 ELSE NULL END) STORED, ADD UNIQUE KEY uk_audit_biz_open(biz_type,biz_id,open_guard)");
        rejected();
    }
    @Test void escapedBacktickLookalikeStatusCannotImpersonateGuard() {
        jdbc.execute("ALTER TABLE audit_task DROP INDEX uk_audit_biz_open, DROP COLUMN open_guard, ADD COLUMN `sta``tus` TINYINT NULL");
        jdbc.execute("ALTER TABLE audit_task ADD COLUMN open_guard TINYINT GENERATED ALWAYS AS (CASE WHEN `sta``tus` IN (0,3) THEN 1 ELSE NULL END) STORED, ADD UNIQUE KEY uk_audit_biz_open(biz_type,biz_id,open_guard)");rejected();
    }
    @Test void triggerLiteralCaseAndWhitespaceAreNotErased() {
        jdbc.execute("DROP TRIGGER content_capture_review_no_update");
        jdbc.execute("CREATE TRIGGER content_capture_review_no_update BEFORE UPDATE ON content_capture_review_binding FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='candidate  review bindings cannot be updated'");rejected();
    }
    @Test void temporaryBindingShadowFailsOnSameConnection(){temporaryShadow("content_capture_review_binding");}
    @Test void temporaryAuditTaskShadowFailsOnSameConnection(){temporaryShadow("audit_task");}
    private void temporaryShadow(String table) {
        fixture.run(()->{java.sql.Connection c=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source);
            try(java.sql.Statement statement=c.createStatement()) {
                // MySQL rejects CREATE TEMPORARY TABLE name LIKE name as a duplicate alias.
                // Read the permanent definition first, then preserve every column/index/guard
                // while changing only CREATE TABLE to CREATE TEMPORARY TABLE on this connection.
                String permanentDefinition;
                try(java.sql.ResultSet definition=statement.executeQuery("SHOW CREATE TABLE `"+table+"`")) {
                    assertTrue(definition.next());permanentDefinition=definition.getString(2);
                    assertTrue(permanentDefinition.startsWith("CREATE TABLE `"+table+"`"));
                    assertFalse(definition.next());
                }
                statement.execute("CREATE TEMPORARY TABLE"+permanentDefinition.substring("CREATE TABLE".length()));
                try {
                    try(java.sql.ResultSet shadow=statement.executeQuery("SHOW CREATE TABLE `"+table+"`")) {
                        assertTrue(shadow.next());assertTrue(shadow.getString(2).startsWith("CREATE TEMPORARY TABLE `"+table+"`"));
                    }
                    IllegalStateException rejected=assertThrows(IllegalStateException.class,guard::verifyCurrentTransaction);
                    assertTrue(rejected.getMessage().contains("temporary shadow"),"The guard must reject the real shadow, not an unrelated metadata error");
                }
                finally {statement.execute("DROP TEMPORARY TABLE `"+table+"`");}
            }catch(java.sql.SQLException e){throw new RuntimeException(e);}
            finally {org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(c,source);}
            return null;});
        accepted();
    }
    @Test void submitReviewLockInversionRollsBackAndExplicitRetryRevalidates() throws Exception {
        long first=fixture.submit("first-lock");fixture.snapshot.setId(null);fixture.snapshot.setRequestId("second-lock-capture");
        fixture.run(()->fixture.session.getMapper(com.chengyinhub.business.mapper.ContentCaptureSnapshotMapper.class).insertFrozen(fixture.snapshot));
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch topicLocked=new CountDownLatch(1),auditLocked=new CountDownLatch(1);
        try {
            Future<Boolean> submit=pool.submit(()->{try{return fixture.run(()->{
                jdbc.queryForObject("SELECT id FROM cms_topic WHERE id=10 FOR UPDATE",Long.class);topicLocked.countDown();
                try{assertTrue(auditLocked.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                fixture.service.submit(10,7,fixture.snapshot.getId(),4,fixture.snapshot.getContentHash(),"second-lock");return true;
            });}catch(org.springframework.dao.DataAccessException conflict){return false;}});
            Future<Boolean> review=pool.submit(()->{try{return fixture.run(()->{
                try{assertTrue(topicLocked.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                jdbc.queryForObject("SELECT id FROM audit_task WHERE id=? FOR UPDATE",Long.class,first);auditLocked.countDown();
                fixture.service.lockAndValidateReview(first,90,fixture.request(0));return true;
            });}catch(org.springframework.dao.DataAccessException conflict){return false;}});
            assertFalse(submit.get(20,TimeUnit.SECONDS));review.get(20,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_review_binding",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT status FROM audit_task WHERE id=?",Integer.class,first));
            fixture.approve(first);assertNotEquals(first,fixture.submit("second-lock"));
        } finally {topicLocked.countDown();auditLocked.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void pendingDdlCannotLeavePreflightWaitingIndefinitely() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong ddlConnection=new java.util.concurrent.atomic.AtomicLong();
        try {
            Future<?> holder=pool.submit(()->fixture.run(()->{guard.verifyCurrentTransaction();held.countDown();try{assertTrue(release.await(20,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
            assertTrue(held.await(10,TimeUnit.SECONDS));
            Future<?> ddl=pool.submit(()->{try(java.sql.Connection c=source.getConnection();java.sql.Statement s=c.createStatement()){
                s.setQueryTimeout(15);try(java.sql.ResultSet rs=s.executeQuery("SELECT CONNECTION_ID()")){assertTrue(rs.next());ddlConnection.set(rs.getLong(1));}
                s.execute("ALTER TABLE content_capture_review_binding ADD COLUMN fixture_drift BIGINT NULL");
            }catch(java.sql.SQLException e){throw new RuntimeException(e);}});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
            while(System.nanoTime()<deadline){
                if(ddlConnection.get()>0 && jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.processlist WHERE id=? AND state='Waiting for table metadata lock'",Integer.class,ddlConnection.get())==1){waiting=true;break;}
                Thread.sleep(10);
            }
            assertTrue(waiting,"DDL must be observed waiting before the bounded preflight probe");
            long start=System.nanoTime();rejected();assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(8));
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(source));
            release.countDown();holder.get(10,TimeUnit.SECONDS);ddl.get(10,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("SELECT 1",Integer.class));
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void auditGuardNonuniqueIndexFails(){jdbc.execute("ALTER TABLE audit_task DROP INDEX uk_audit_biz_open, ADD KEY uk_audit_biz_open(biz_type,biz_id,open_guard)");rejected();}
    @Test void auditGuardWrongIndexOrderFails(){jdbc.execute("ALTER TABLE audit_task DROP INDEX uk_audit_biz_open, ADD UNIQUE KEY uk_audit_biz_open(biz_id,biz_type,open_guard)");rejected();}
    @Test void auditNullableBusinessIdFails(){jdbc.execute("ALTER TABLE audit_task MODIFY biz_id BIGINT NULL");rejected();}
    @Test void unexpectedCascadeForeignKeyFails(){jdbc.execute("CREATE TABLE fixture_parent(id BIGINT PRIMARY KEY) ENGINE=InnoDB");jdbc.execute("ALTER TABLE content_capture_review_binding ADD CONSTRAINT fk_unapproved_parent FOREIGN KEY(snapshot_id) REFERENCES fixture_parent(id) ON DELETE CASCADE");rejected();}
    @Test void mutableCreationTimestampFails(){jdbc.execute("ALTER TABLE content_capture_review_binding MODIFY created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)");rejected();}
    @Test void missingTriggerStopsActualSubmissionBeforeAnyAuditWrite(){jdbc.execute("DROP TRIGGER content_capture_review_no_update");assertThrows(IllegalStateException.class,()->fixture.submit("blocked"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_review_binding",Integer.class));}
    @Test void verifiedTransactionMetadataLockBlocksConcurrentDdl() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch verified=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            Future<?> held=pool.submit(()->fixture.run(()->{guard.verifyCurrentTransaction();verified.countDown();try{assertTrue(release.await(20,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
            assertTrue(verified.await(10,TimeUnit.SECONDS));
            Future<?> ddl=pool.submit(()->{try(java.sql.Connection c=source.getConnection();java.sql.Statement s=c.createStatement()){
                s.execute("SET SESSION lock_wait_timeout=1");
                java.sql.SQLException denied=assertThrows(java.sql.SQLException.class,()->s.execute("ALTER TABLE content_capture_review_binding DROP INDEX uk_capture_review_snapshot"));
                assertEquals(1205,denied.getErrorCode());
            }catch(java.sql.SQLException e){throw new RuntimeException(e);}});
            ddl.get(10,TimeUnit.SECONDS);release.countDown();held.get(10,TimeUnit.SECONDS);accepted();
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
}
