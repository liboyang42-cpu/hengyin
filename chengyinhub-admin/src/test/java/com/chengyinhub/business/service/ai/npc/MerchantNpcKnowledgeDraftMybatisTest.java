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

/** H2 MySQL-mode fixture, real MyBatis XML + Spring transactions; not MySQL acceptance evidence. */
public class MerchantNpcKnowledgeDraftMybatisTest {
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
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:merchant_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        jdbc=new JdbcTemplate(ds); transactionManager=new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE ALIAS SHA2 FOR \""+getClass().getName()+".sha2\"");
        createFixtureTable("mms_merchant","MmsMerchantMapper.xml");
        createFixtureTable("audit_task","AuditTaskMapper.xml");
        createFixtureTable("audit_record","AuditRecordMapper.xml");
        createFixtureTable("business_audit_event","BusinessAuditEventMapper.xml");
        jdbc.execute("CREATE UNIQUE INDEX audit_request ON business_audit_event(domain_type,domain_id,action,request_id)");
        java.nio.file.Path repositoryRoot=Paths.get("").toAbsolutePath();
        while(repositoryRoot!=null && !Files.isRegularFile(repositoryRoot.resolve("chengyinhub-admin/sql/migration_20261004_merchant_npc_knowledge_drafts.sql")))
            repositoryRoot=repositoryRoot.getParent();
        assertNotNull(repositoryRoot,"The exact candidate migration must be present");
        String migration=new String(Files.readAllBytes(repositoryRoot.resolve("chengyinhub-admin/sql/migration_20261004_merchant_npc_knowledge_drafts.sql")),StandardCharsets.UTF_8);
        // H2 lacks per-column MySQL charsets/collations; table definitions and constraints are otherwise unchanged.
        migration=migration.replaceAll("(?m)^--.*$", "").replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        for(String sql:migration.split(";")) if(!sql.trim().isEmpty()) jdbc.execute(sql);
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
    void createFixtureTable(String table,String mapper) throws Exception {
        String xml;
        try(InputStream in=getClass().getClassLoader().getResourceAsStream("mapper/business/"+mapper)) {
            assertNotNull(in,"The production mapper resource must be present");
            xml=org.springframework.util.StreamUtils.copyToString(in,StandardCharsets.UTF_8);
        }
        String first=xml.substring(xml.indexOf("<resultMap"),xml.indexOf("</resultMap>"));
        Matcher matches=Pattern.compile("column=\"([^\"]+)\"").matcher(first);
        List<String> cols=new ArrayList<>(); Set<String> seen=new HashSet<>();
        while(matches.find()) {
            String c=matches.group(1); if(!seen.add(c))continue;
            String type=c.equals("id")?"BIGINT AUTO_INCREMENT PRIMARY KEY":
                c.endsWith("_time")||c.endsWith("_at")?"TIMESTAMP":
                (c.endsWith("_id") && !c.equals("request_id"))||Arrays.asList("status","account_status","del_flag","version","current_assignee").contains(c)?"BIGINT":"VARCHAR(30000)";
            cols.add(c+" "+type);
        }
        jdbc.execute("CREATE TABLE "+table+"("+String.join(",",cols)+")");
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
        jdbc.execute("ALTER TABLE merchant_npc_knowledge_revision DROP CONSTRAINT fail_binding");
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

    MerchantNpcKnowledgeDraft pendingPreview() {
        MerchantNpcKnowledgeDraft row=save("preview");
        return service.submitForReview(10,20,1,row.getContentHash());
    }
    MerchantNpcKnowledgePreview preview(MerchantNpcKnowledgeDraft row) {
        return service.previewForReview(10,20,row.getVersion(),row.getContentHash(),row.getAuditTaskId(),0,700);
    }
    List<Object> persistedState() {
        List<Object> result=new ArrayList<>();
        for(String table:Arrays.asList("mms_merchant","merchant_npc_knowledge_revision","merchant_npc_knowledge_head",
                "audit_task","audit_record","business_audit_event")) result.add(jdbc.queryForList("SELECT * FROM "+table));
        return result;
    }
    @Test void previewReturnsExactMinimalImmutableEvidenceWithoutWrites() throws Exception {
        MerchantNpcKnowledgeDraft row=pendingPreview(); List<Object> before=persistedState();
        MerchantNpcKnowledgePreview dto=preview(row);
        assertEquals(before,persistedState());
        assertEquals(row.getContentJson(),dto.getPublicFactsDraftJson());
        assertEquals(row.getContentHash(),dto.getContentHash());
        assertEquals(row.getId().toString(),dto.getRevisionId());
        assertEquals(row.getAuditTaskId().toString(),dto.getAuditTaskId());
        assertEquals("1",dto.getRevisionVersion()); assertEquals("10",dto.getMerchantId());
        assertEquals(0,dto.getAuditTaskVersion()); assertEquals("PENDING",dto.getStatus());
        assertFalse(dto.isRuntimePublication());
        com.fasterxml.jackson.databind.JsonNode json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(dto);
        Set<String> keys=new HashSet<>(); json.fieldNames().forEachRemaining(keys::add);
        assertEquals(new HashSet<>(Arrays.asList("contract","auditTaskId","auditTaskVersion","merchantId","revisionId",
                "revisionVersion","contentHash","publicFactsDraftJson","status","textEncoding","runtimePublication")),keys);
        assertFalse(json.toString().contains("PRIVATE PROFILE"));
        assertEquals("questify.merchant-npc-review-preview.v1",dto.getContract());
        assertEquals("UNTRUSTED_JSON_PLAIN_TEXT",dto.getTextEncoding());
        for(java.lang.reflect.Field f:MerchantNpcKnowledgePreview.class.getDeclaredFields())
            assertTrue(java.lang.reflect.Modifier.isFinal(f.getModifiers()));
    }
    @Test void previewRequiresFreshReviewerAndInstalledRouteEveryTime() {
        MerchantNpcKnowledgeDraft row=pendingPreview(); preview(row); clearInvocations(authority);
        preview(row); verify(authority).requireCurrentReviewer(700,10); verify(authority).requireInstalledReviewRoute(10,20);
        doThrow(new SecurityException("revoked")).when(authority).requireCurrentReviewer(700,10);
        List<Object> before=persistedState(); assertThrows(SecurityException.class,()->preview(row)); assertEquals(before,persistedState());
        reset(authority); doThrow(new SecurityException("route removed")).when(authority).requireInstalledReviewRoute(10,20);
        assertThrows(SecurityException.class,()->preview(row));
        ReflectionTestUtils.setField(target,"reviewAuthority",null); assertThrows(IllegalStateException.class,()->preview(row));
    }
    @Test void previewRejectsStaleTaskVersionAndEveryNonPendingState() {
        MerchantNpcKnowledgeDraft row=pendingPreview();
        assertThrows(IllegalStateException.class,()->service.previewForReview(10,20,1,row.getContentHash(),row.getAuditTaskId(),1,700));
        jdbc.update("UPDATE audit_task SET version=1"); assertThrows(IllegalStateException.class,()->preview(row));
        for(int status:Arrays.asList(1,2,3,4)) {
            jdbc.update("UPDATE audit_task SET version=0,status=?",status);
            assertThrows(IllegalStateException.class,()->preview(row));
        }
    }
    @Test void previewRejectsChangedOwnerAndMerchantEligibility() {
        MerchantNpcKnowledgeDraft row=pendingPreview();
        for(String assignment:Arrays.asList("member_id=99","status=0","account_status=0","del_flag=1")) {
            jdbc.update("UPDATE mms_merchant SET "+assignment+" WHERE id=10");
            assertThrows(IllegalStateException.class,()->preview(row));
            jdbc.update("UPDATE mms_merchant SET member_id=20,status=1,account_status=1,del_flag=0 WHERE id=10");
        }
        jdbc.update("DELETE FROM mms_merchant WHERE id=10"); assertThrows(IllegalStateException.class,()->preview(row));
    }
    @Test void previewNeverSubstitutesNewHeadForRequestedRevision() {
        MerchantNpcKnowledgeDraft row=pendingPreview();
        service.save(10,20,1,"newer",FACTS.replace("1800","1900"));
        assertThrows(IllegalStateException.class,()->preview(row));
    }
    @Test void previewRejectsEveryForgedAuditBindingComponent() {
        MerchantNpcKnowledgeDraft row=pendingPreview();
        AuditTask original=tasks.selectAuditTaskById(row.getAuditTaskId());
        for(String assignment:Arrays.asList("biz_type='TOPIC'","source_type='profile_approval'","biz_id=999","submitter_id=99","snapshot_json='{}'")) {
            jdbc.update("UPDATE audit_task SET "+assignment); assertThrows(IllegalStateException.class,()->preview(row));
            jdbc.update("UPDATE audit_task SET biz_type=?,source_type=?,biz_id=?,submitter_id=?,snapshot_json=?",
                    original.getBizType(),original.getSourceType(),original.getBizId(),original.getSubmitterId(),original.getSnapshotJson());
        }
        jdbc.update("UPDATE merchant_npc_knowledge_revision SET audit_task_id=NULL"); assertThrows(IllegalStateException.class,()->preview(row));
    }
    @Test void previewRejectsInvalidCallerBindingsWithoutWrites() {
        MerchantNpcKnowledgeDraft row=pendingPreview(); List<Object> before=persistedState();
        assertThrows(IllegalStateException.class,()->service.previewForReview(11,21,1,row.getContentHash(),row.getAuditTaskId(),0,700));
        assertThrows(IllegalStateException.class,()->service.previewForReview(10,99,1,row.getContentHash(),row.getAuditTaskId(),0,700));
        assertThrows(IllegalStateException.class,()->service.previewForReview(10,20,2,row.getContentHash(),row.getAuditTaskId(),0,700));
        assertThrows(IllegalStateException.class,()->service.previewForReview(10,20,1,row.getContentHash(),999,0,700));
        assertThrows(IllegalStateException.class,()->service.previewForReview(10,20,1,"bad",row.getAuditTaskId(),0,700));
        assertThrows(IllegalStateException.class,()->service.previewForReview(10,20,1,row.getContentHash(),row.getAuditTaskId(),-1,700));
        assertThrows(IllegalStateException.class,()->service.previewForReview(10,20,1,row.getContentHash(),row.getAuditTaskId(),0,0));
        assertEquals(before,persistedState());
    }
    @Test void previewRechecksCanonicalContentAndHash() {
        MerchantNpcKnowledgeDraft row=pendingPreview();
        jdbc.update("UPDATE merchant_npc_knowledge_revision SET content_json='{}'");
        assertThrows(IllegalArgumentException.class,()->preview(row));
        jdbc.update("UPDATE merchant_npc_knowledge_revision SET content_json=?",row.getContentJson()+" ");
        assertThrows(IllegalStateException.class,()->preview(row));
        jdbc.update("UPDATE merchant_npc_knowledge_revision SET content_json=?",row.getContentJson().replace("1800","1900"));
        assertThrows(IllegalStateException.class,()->preview(row));
    }
    @Test void previewRequiresEnablementAndLockCapableTransaction() {
        MerchantNpcKnowledgeDraft row=pendingPreview();
        ReflectionTestUtils.setField(target,"enabled",false); assertThrows(IllegalStateException.class,()->preview(row));
        ReflectionTestUtils.setField(target,"enabled",true);
        assertThrows(IllegalStateException.class,()->target.previewForReview(10,20,1,row.getContentHash(),row.getAuditTaskId(),0,700));
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.setReadOnly(true); assertThrows(IllegalStateException.class,()->tx.execute(status->preview(row)));
    }
    @Test void previewTextRemainsUntrustedAndNeverExecutesOrNormalizesMarkup() {
        String text=FACTS.replace("Tea week","<script>alert(1)</script>");
        MerchantNpcKnowledgeDraft row=service.save(10,20,0,"markup",text);
        row=service.submitForReview(10,20,1,row.getContentHash());
        MerchantNpcKnowledgePreview dto=preview(row);
        assertTrue(dto.getPublicFactsDraftJson().contains("<script>"));
        assertEquals(row.getContentJson(),dto.getPublicFactsDraftJson());
        assertFalse(dto.isRuntimePublication());
    }

}
