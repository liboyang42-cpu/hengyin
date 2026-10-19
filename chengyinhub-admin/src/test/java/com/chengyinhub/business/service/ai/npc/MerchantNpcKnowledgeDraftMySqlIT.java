package com.chengyinhub.business.service.ai.npc;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Oracle MySQL only. Real mapper XML, exact candidate migration, constrained historical fixtures.
 * Revision immutability is tested through the service, not claimed as a database trigger guarantee. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
public class MerchantNpcKnowledgeDraftMySqlIT {
    static final String FACTS="{\"schemaVersion\":1,\"products\":[{\"name\":\"Tea\",\"priceMinor\":1800,\"currency\":\"CNY\"}],"
        +"\"hours\":[{\"day\":1,\"opens\":\"09:00\",\"closes\":\"18:00\",\"timeZone\":\"Asia/Shanghai\"}],"
        +"\"promotions\":[{\"title\":\"Tea week\",\"startsAt\":\"2026-10-01T00:00:00Z\",\"endsAt\":\"2026-10-08T00:00:00Z\"}],"
        +"\"faq\":[{\"question\":\"Allergens?\",\"answer\":\"Ask staff to confirm.\"}],\"neverSay\":[\"Do not promise allergen safety\"]}";
    JdbcTemplate jdbc;
    MerchantNpcKnowledgeDraftService service,target;
    MerchantNpcKnowledgeDraftMapper drafts;
    BusinessAuditEventMapper events;
    AuditTaskMapper tasks;
    AuditRecordMapper records;
    MmsMerchantMapper merchants;
    MerchantNpcKnowledgeReviewAuthority authority;
    DataSourceTransactionManager transactionManager;
    @BeforeEach void setup() throws Exception {
        schema=new com.chengyinhub.business.it.support.IsolatedMySqlSchema();
        DriverManagerDataSource ds=schema.open("npc_draft"); source=ds;
        jdbc=new JdbcTemplate(ds); transactionManager=new DataSourceTransactionManager(ds);
        schema.apply("business_audit_event_20260823");
        String fixture=resource("fixtures/merchant-npc-existing-schema.sql");
        assertEquals(FIXTURE_SHA256,sha2(fixture,256));
        // The fixture contains only verbatim CREATE/ALTER DDL without stored routines.
        for(String sql:fixture.replaceAll("(?m)^--.*$", "").split(";"))
            if(!sql.trim().isEmpty()) jdbc.execute(sql);
        schema.apply("merchant_service_line_20260820");
        schema.apply("merchant_derivative_benefits_20260912");
        schema.apply("20260915_merchant_disable_reason");
        Path root=repositoryRoot();
        byte[] ddl=Files.readAllBytes(root.resolve("chengyinhub-admin/sql/migration_20261004_merchant_npc_knowledge_drafts.sql"));
        assertEquals(DDL_SHA256,sha2(new String(ddl,StandardCharsets.UTF_8),256));
        schema.apply("20261004_merchant_npc_knowledge_drafts");
        proofSchema=jdbc.queryForObject("SELECT DATABASE()",String.class);
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
        jdbc.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag,description) VALUES(10,20,1,1,0,'PRIVATE PROFILE TEXT')");
        jdbc.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag) VALUES(11,21,1,1,0)");
        Configuration cfg=new Configuration(new Environment("merchant",new SpringManagedTransactionFactory(),ds));
        cfg.getTypeAliasRegistry().registerAlias("MmsMerchant",MmsMerchant.class);
        cfg.getTypeAliasRegistry().registerAlias("AuditTask",AuditTask.class);
        cfg.getTypeAliasRegistry().registerAlias("AuditRecord",AuditRecord.class);
        for(String name:Arrays.asList("MmsMerchant","MerchantNpcKnowledgeDraft","AuditTask","AuditRecord","BusinessAuditEvent")) {
            String resource="mapper/business/"+name+"Mapper.xml";
            try(InputStream in=getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(in); new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        merchants=session.getMapper(MmsMerchantMapper.class); drafts=session.getMapper(MerchantNpcKnowledgeDraftMapper.class);
        events=session.getMapper(BusinessAuditEventMapper.class); tasks=session.getMapper(AuditTaskMapper.class);
        records=session.getMapper(AuditRecordMapper.class);
        target=new MerchantNpcKnowledgeDraftService(merchants,drafts,events,tasks,records);
        authority=mock(MerchantNpcKnowledgeReviewAuthority.class);
        ReflectionTestUtils.setField(target,"enabled",true); ReflectionTestUtils.setField(target,"reviewAuthority",authority);
        service=proxy(target);
    }
    MerchantNpcKnowledgeDraftService proxy(MerchantNpcKnowledgeDraftService instance) {
        ProxyFactory factory=new ProxyFactory(instance); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager,new AnnotationTransactionAttributeSource()));
        return (MerchantNpcKnowledgeDraftService)factory.getProxy();
    }
    static final String FIXTURE_SHA256="ff8f708bd05b5174d5a8ba5ac76c0e495917bb725a0fa083cec120ef900e205c";
    static final String DDL_SHA256="8aa009a719247e23647affae8b0324a1c60b1e10e77842eb3ba3bf7985c87c5c";
    com.chengyinhub.business.it.support.IsolatedMySqlSchema schema;
    javax.sql.DataSource source;
    String proofSchema;
    static Path repositoryRoot() {
        Path root=Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if(root.getFileName().toString().equals("chengyinhub-admin"))root=root.getParent();
        return root;
    }
    static String resource(String path)throws Exception {
        try(InputStream in=MerchantNpcKnowledgeDraftMySqlIT.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in,path); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    @org.junit.jupiter.api.AfterEach void cleanup(org.junit.jupiter.api.TestInfo test)throws Exception {
        if(schema!=null)schema.close();
        if(proofSchema!=null) {
            String commit=System.getProperty("npc.draft.test.commit"),nonce=System.getProperty("npc.draft.test.run");
            assertNotNull(commit,"Proof requires exact tested commit");
            assertTrue(commit.matches("[0-9a-f]{40}"));
            assertNotNull(nonce,"Proof requires run-specific nonce");
            assertTrue(nonce.matches("[0-9a-f]{32}"));
            System.out.println("NPC_DRAFT_MYSQL_EVIDENCE METHOD="+test.getTestMethod().get().getName()
                +" VERSION=8.0.45 SCHEMA="+proofSchema+" MIGRATION_SHA256="+DDL_SHA256
                +" FIXTURE_SHA256="+FIXTURE_SHA256+" COMMIT="+commit+" RUN="+nonce+" SCRATCH_DROPPED=true");
        }
    }
    public static String sha2(String s,int bits) throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)))out.append(String.format("%02x",b&255));return out.toString();
    }
    MerchantNpcKnowledgeDraft save(String key) { return service.save(10,20,0,key,FACTS); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    @Test void savesImmutableRevisionAndReadbackWithActualOwnerMapper() {
        MerchantNpcKnowledgeDraft a=save("save-1");
        assertEquals(1L,a.getVersion()); assertEquals(a.getContentHash(),service.read(10,20,1,a.getContentHash()).getContentHash());
        assertFalse(a.getContentJson().contains("PRIVATE PROFILE")); assertNull(a.getAuditTaskId());
        assertEquals(1,count("merchant_npc_knowledge_revision")); assertEquals(1,count("business_audit_event"));
        assertEquals(0,count("audit_task"));
        assertFalse(jdbc.queryForObject("SELECT after_json FROM business_audit_event",String.class).contains("Tea"));
    }
    @Test void newRevisionPreservesOldContentAndRejectsStaleCas() {
        MerchantNpcKnowledgeDraft a=save("first");
        MerchantNpcKnowledgeDraft b=service.save(10,20,1,"second",FACTS.replace("1800","1900"));
        assertEquals(2L,b.getVersion()); assertNotEquals(a.getContentHash(),b.getContentHash());
        assertEquals(a.getContentJson(),service.read(10,20,1,a.getContentHash()).getContentJson());
        assertThrows(IllegalStateException.class,()->service.save(10,20,1,"stale",FACTS));
        assertEquals(2,count("merchant_npc_knowledge_revision"));
    }
    @Test void retryIsBoundToOriginalPayloadAndVersion() {
        MerchantNpcKnowledgeDraft a=save("same"); assertEquals(a.getId(),save("same").getId());
        assertThrows(IllegalStateException.class,()->service.save(10,20,0,"same",FACTS.replace("1800","1900")));
        assertThrows(IllegalStateException.class,()->service.save(10,20,1,"same",FACTS));
        assertEquals(1,count("business_audit_event"));
    }
    @Test void changedOwnerFencesReadsWritesReplayAndReview() {
        MerchantNpcKnowledgeDraft a=save("one"); jdbc.update("UPDATE mms_merchant SET member_id=99 WHERE id=10");
        assertThrows(IllegalStateException.class,()->save("one"));
        assertThrows(IllegalStateException.class,()->service.read(10,20,1,a.getContentHash()));
        assertThrows(IllegalStateException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
        assertThrows(IllegalStateException.class,()->service.save(10,99,1,"new-owner",FACTS));
        assertThrows(IllegalStateException.class,()->service.read(10,99,1,a.getContentHash()));
        assertEquals(0,count("audit_task"));
    }
    @Test void crossMerchantAndDisabledMerchantFailClosed() {
        MerchantNpcKnowledgeDraft a=save("one");
        assertThrows(IllegalStateException.class,()->service.read(11,21,1,a.getContentHash()));
        assertThrows(IllegalStateException.class,()->service.save(10,21,1,"foreign",FACTS));
        jdbc.update("UPDATE mms_merchant SET account_status=0 WHERE id=10");
        assertThrows(IllegalStateException.class,()->service.read(10,20,1,a.getContentHash()));
        assertThrows(IllegalStateException.class,()->save("one"));
    }
    @Test void featureOffAndMissingTransactionCannotWrite() {
        ReflectionTestUtils.setField(target,"enabled",false);
        assertThrows(IllegalStateException.class,()->save("off"));
        ReflectionTestUtils.setField(target,"enabled",true);
        assertThrows(IllegalStateException.class,()->target.save(10,20,0,"direct",FACTS));
        assertEquals(0,count("merchant_npc_knowledge_revision"));
    }
    @Test void missingReviewAuthorityDoesNotCreateTasks() {
        MerchantNpcKnowledgeDraft a=save("one"); ReflectionTestUtils.setField(target,"reviewAuthority",null);
        assertThrows(IllegalStateException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
        assertEquals(0,count("audit_task")); assertNull(service.read(10,20,1,a.getContentHash()).getAuditTaskId());
    }
    @Test void submitsExactPendingCandidateToExistingAuditTablesAndReplays() {
        MerchantNpcKnowledgeDraft a=save("one"); MerchantNpcKnowledgeDraft b=service.submitForReview(10,20,1,a.getContentHash());
        assertNotNull(b.getAuditTaskId()); assertEquals(b.getAuditTaskId(),service.submitForReview(10,20,1,a.getContentHash()).getAuditTaskId());
        assertEquals(1,count("audit_task")); assertEquals(1,count("audit_record")); assertEquals(2,count("business_audit_event"));
        assertEquals(MerchantNpcKnowledgeDraftService.BIZ_TYPE,jdbc.queryForObject("SELECT biz_type FROM audit_task",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT status FROM audit_task",Integer.class));
        assertTrue(jdbc.queryForObject("SELECT snapshot_json FROM audit_task",String.class).contains("\"runtimePublication\":false"));
        service.validateReview(10,20,1,a.getContentHash(),b.getAuditTaskId(),700);
        verify(authority).requireCurrentReviewer(700,10);
    }
    @Test void staleVersionCannotSubmitOrReviewAfterHeadMoves() {
        MerchantNpcKnowledgeDraft a=save("one"); MerchantNpcKnowledgeDraft submitted=service.submitForReview(10,20,1,a.getContentHash());
        service.save(10,20,1,"two",FACTS.replace("1800","2000"));
        assertThrows(IllegalStateException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
        assertThrows(IllegalStateException.class,()->service.validateReview(10,20,1,a.getContentHash(),submitted.getAuditTaskId(),700));
    }
    @Test void alteredSnapshotOrSourceTypeCannotReplay() {
        MerchantNpcKnowledgeDraft a=save("one"); service.submitForReview(10,20,1,a.getContentHash());
        jdbc.update("UPDATE audit_task SET source_type='profile_approval'");
        assertThrows(IllegalStateException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
        jdbc.update("UPDATE audit_task SET source_type=?, snapshot_json='{}'",MerchantNpcKnowledgeDraftService.SOURCE_TYPE);
        assertThrows(IllegalStateException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
    }
    @Test void persistedContentTamperingFailsHashReadback() {
        MerchantNpcKnowledgeDraft a=save("one"); jdbc.update("UPDATE merchant_npc_knowledge_revision SET content_json=?",FACTS.replace("1800","1"));
        assertThrows(IllegalStateException.class,()->service.read(10,20,1,a.getContentHash()));
        assertThrows(IllegalStateException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
    }
    @Test void saveAuditFailureRollsBackRevisionAndHead() {
        jdbc.execute("ALTER TABLE business_audit_event ADD CONSTRAINT fail_save CHECK(action <> 'NPC_KNOWLEDGE_DRAFT_SAVE')");
        assertThrows(RuntimeException.class,()->save("one")); assertEquals(0,count("merchant_npc_knowledge_revision"));
        assertEquals(0,count("merchant_npc_knowledge_head"));
    }
    @Test void submitAuditRecordFailureRollsBackTaskAndBinding() {
        MerchantNpcKnowledgeDraft a=save("one"); jdbc.execute("ALTER TABLE audit_record ADD CONSTRAINT fail_submit CHECK(action <> 'SUBMIT')");
        assertThrows(RuntimeException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
        assertEquals(0,count("audit_task")); assertNull(service.read(10,20,1,a.getContentHash()).getAuditTaskId());
        assertEquals(1,count("business_audit_event"));
    }
    @Test void deniedReviewerCannotValidate() {
        MerchantNpcKnowledgeDraft a=save("one"); MerchantNpcKnowledgeDraft b=service.submitForReview(10,20,1,a.getContentHash());
        doThrow(new SecurityException("not authorized")).when(authority).requireCurrentReviewer(700,10);
        assertThrows(SecurityException.class,()->service.validateReview(10,20,1,a.getContentHash(),b.getAuditTaskId(),700));
    }
    @Test void concurrentSavesWithSameExpectedVersionCommitOnlyOnce() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<2;i++) { final String key="concurrent-"+i; results.add(pool.submit(()->{
                start.await(); try {save(key);return true;}catch(IllegalStateException e){return false;}
            })); }
            start.countDown(); int succeeded=0; for(Future<Boolean> result:results)if(result.get(15,TimeUnit.SECONDS))succeeded++;
            assertEquals(1,succeeded); assertEquals(1,count("merchant_npc_knowledge_revision")); assertEquals(1,count("business_audit_event"));
        } finally { pool.shutdownNow(); }
    }
    @Test void sqlCasAndBindingAreOwnerFencedEvenWithoutService() {
        MerchantNpcKnowledgeDraft a=save("one"); a.setOwnerMemberId(99L); a.setVersion(2L); a.setAuditTaskId(42L);
        assertEquals(0,drafts.advanceHead(a,1)); assertEquals(0,drafts.bindReview(a));
        assertEquals(1L,jdbc.queryForObject("SELECT version FROM merchant_npc_knowledge_head",Long.class));
    }
    @Test void failedHeadWriteRollsBackRevision() {
        jdbc.execute("ALTER TABLE merchant_npc_knowledge_head ADD CONSTRAINT fail_head CHECK(version <> 1)");
        assertThrows(RuntimeException.class,()->save("one"));
        assertEquals(0,count("merchant_npc_knowledge_revision")); assertEquals(0,count("business_audit_event"));
    }
    @Test void failedRevisionWriteLeavesNoHeadOrAudit() {
        jdbc.execute("ALTER TABLE merchant_npc_knowledge_revision ADD CONSTRAINT fail_revision CHECK(version <> 1)");
        assertThrows(RuntimeException.class,()->save("one"));
        assertEquals(0,count("merchant_npc_knowledge_head")); assertEquals(0,count("business_audit_event"));
    }
    @Test void failedBindingAndSubmitEventRollBackWholeHandoff() {
        MerchantNpcKnowledgeDraft a=save("one");
        jdbc.execute("ALTER TABLE merchant_npc_knowledge_revision ADD CONSTRAINT fail_binding CHECK(audit_task_id IS NULL)");
        assertThrows(RuntimeException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
        assertEquals(0,count("audit_task")); assertEquals(0,count("audit_record"));
        jdbc.execute("ALTER TABLE merchant_npc_knowledge_revision DROP CHECK fail_binding");
        jdbc.execute("ALTER TABLE business_audit_event ADD CONSTRAINT fail_event CHECK(action <> 'NPC_KNOWLEDGE_DRAFT_SUBMIT')");
        assertThrows(RuntimeException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
        assertEquals(0,count("audit_task")); assertEquals(0,count("audit_record"));
        assertNull(service.read(10,20,1,a.getContentHash()).getAuditTaskId());
    }
    @Test void forgedTaskIdentityAndHashFailClosed() {
        MerchantNpcKnowledgeDraft a=save("one"); MerchantNpcKnowledgeDraft b=service.submitForReview(10,20,1,a.getContentHash());
        assertThrows(IllegalStateException.class,()->service.validateReview(10,20,1,a.getContentHash(),b.getAuditTaskId()+1,700));
        assertThrows(IllegalStateException.class,()->service.submitForReview(10,20,1,new String(new char[64]).replace('\0','0')));
        jdbc.update("UPDATE audit_task SET submitter_id=21");
        assertThrows(IllegalStateException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
        jdbc.update("UPDATE audit_task SET submitter_id=20, biz_type='TOPIC'");
        assertThrows(IllegalStateException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
    }
    @Test void readOnlyTransactionStopsBeforePersistence() {
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        assertThrows(IllegalStateException.class,()->tx.execute(status->target.save(10,20,0,"readonly",FACTS)));
        assertEquals(0,count("merchant_npc_knowledge_revision"));
    }
    @Test void retryAfterNewHeadReturnsOriginalReceiptWithoutRewinding() {
        MerchantNpcKnowledgeDraft a=save("original");
        service.save(10,20,1,"new-head",FACTS.replace("1800","1900"));
        assertEquals(a.getId(),save("original").getId());
        assertEquals(2L,jdbc.queryForObject("SELECT version FROM merchant_npc_knowledge_head",Long.class));
        assertEquals(2,count("business_audit_event"));
    }
    @Test void concurrentSameRevisionSubmissionsCreateOneTaskAndRecord() throws Exception {
        MerchantNpcKnowledgeDraft a=save("one"); ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Long> call=()->{start.await();return service.submitForReview(10,20,1,a.getContentHash()).getAuditTaskId();};
            Future<Long> first=pool.submit(call),second=pool.submit(call); start.countDown();
            assertEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            assertEquals(1,count("audit_task")); assertEquals(1,count("audit_record"));
            assertEquals(2,count("business_audit_event"));
        } finally {pool.shutdownNow();}
    }

    @Test void malformedPublicFactsCannotPersistOrBorrowPrivateProfileFields() {
        for(String invalid:Arrays.asList("{", "{}", FACTS.replace("1800","-1"),
                FACTS.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"phone\":\"private\""))) {
            assertThrows(IllegalArgumentException.class,()->service.save(10,20,0,"invalid",invalid));
            assertEquals(0,count("merchant_npc_knowledge_revision"));
            assertEquals(0,count("merchant_npc_knowledge_head"));
            assertEquals(0,count("business_audit_event"));
        }
    }
    @Test void everyCurrentMerchantEligibilityFieldFencesExistingReceiptAndReview() {
        MerchantNpcKnowledgeDraft a=save("one");
        MerchantNpcKnowledgeDraft submitted=service.submitForReview(10,20,1,a.getContentHash());
        for(String change:Arrays.asList("status=0","account_status=2","del_flag=2","member_id=99")) {
            jdbc.update("UPDATE mms_merchant SET "+change+" WHERE id=10");
            assertThrows(IllegalStateException.class,()->save("one"));
            assertThrows(IllegalStateException.class,()->service.read(10,20,1,a.getContentHash()));
            assertThrows(IllegalStateException.class,()->service.submitForReview(10,20,1,a.getContentHash()));
            assertThrows(IllegalStateException.class,()->service.validateReview(10,20,1,a.getContentHash(),submitted.getAuditTaskId(),700));
            jdbc.update("UPDATE mms_merchant SET status=1,account_status=1,del_flag=0,member_id=20 WHERE id=10");
        }
        assertEquals(1,count("merchant_npc_knowledge_revision"));assertEquals(1,count("audit_task"));
        assertEquals(1,count("audit_record"));assertEquals(2,count("business_audit_event"));
    }
    @Test void merchantTransferCommittedWhileReplayWaitsCannotReturnOldReceipt() throws Exception {
        MerchantNpcKnowledgeDraft a=save("one");
        ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);
        try(java.sql.Connection c=source.getConnection()) {
            c.setAutoCommit(false);
            try(java.sql.Statement sql=c.createStatement()) {
                sql.executeQuery("SELECT id FROM mms_merchant WHERE id=10 FOR UPDATE").close();
                sql.executeUpdate("UPDATE mms_merchant SET member_id=99 WHERE id=10");
                Future<?> replay=pool.submit(()->{started.countDown();assertThrows(IllegalStateException.class,()->save("one"));});
                assertTrue(started.await(10,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->replay.get(150,TimeUnit.MILLISECONDS));
                c.commit();replay.get(15,TimeUnit.SECONDS);
                assertThrows(IllegalStateException.class,()->service.read(10,20,1,a.getContentHash()));
                assertEquals(1,count("merchant_npc_knowledge_revision"));assertEquals(1,count("business_audit_event"));
            } finally {c.rollback();}
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void actualSchemaRetainsNativeJsonOpenGuardAndStrictRequiredColumns() {
        assertEquals("json",jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='business_audit_event' AND column_name='after_json'",String.class));
        assertEquals("STORED GENERATED",jdbc.queryForObject("SELECT extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='audit_task' AND column_name='open_guard'",String.class));
        assertEquals("NO",jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='business_audit_event' AND column_name='request_id'",String.class));
        assertEquals("ascii_bin",jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='merchant_npc_knowledge_revision' AND column_name='request_id'",String.class));
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND engine='InnoDB'",Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO audit_task(biz_type,biz_id) VALUES('bad',1)"));
        MerchantNpcKnowledgeDraft a=save("one");service.submitForReview(10,20,1,a.getContentHash());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO audit_task(biz_type,biz_id,submitted_at) SELECT biz_type,biz_id,NOW() FROM audit_task"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE business_audit_event SET after_json='not json'"));
        assertEquals(1,count("audit_task"));
    }
    @Test void approvedMigrationCannotSilentlyReuseWeakPreexistingTables() throws Exception {
        assertThrows(java.sql.SQLException.class,()->schema.apply("20261004_merchant_npc_knowledge_drafts"));
        jdbc.execute("DROP TABLE merchant_npc_knowledge_revision");
        jdbc.execute("CREATE TABLE merchant_npc_knowledge_revision(id BIGINT)");
        assertThrows(java.sql.SQLException.class,()->schema.apply("20261004_merchant_npc_knowledge_drafts"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='merchant_npc_knowledge_revision'",Integer.class));
    }
}
