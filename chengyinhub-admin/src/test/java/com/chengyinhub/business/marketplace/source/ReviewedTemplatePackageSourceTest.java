package com.chengyinhub.business.marketplace.source;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.reservation.TextModuleDraftCodec;
import com.chengyinhub.business.service.support.TemplateAuditSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual existing mapper XML + Spring/MyBatis transactions on isolated H2. No source DAO doubles. */
class ReviewedTemplatePackageSourceTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private SqlSessionTemplate session;
    private ReviewedTemplatePackageSource reader;
    private String auditHash;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:w18_source_"+System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        ds.setDriverClassName("org.h2.Driver"); jdbc = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("source",new SpringManagedTransactionFactory(),ds));
        config.getTypeAliasRegistry().registerAlias("CmsMemberTemplate",CmsMemberTemplate.class);
        config.getTypeAliasRegistry().registerAlias("MemberTemplateVO",MemberTemplateVO.class);
        config.getTypeAliasRegistry().registerAlias("AuditTask",AuditTask.class);
        for(String name:Arrays.asList("CmsMemberTemplateMapper","AuditTaskMapper")) {
            String resource="mapper/business/"+name+".xml";
            try(InputStream stream=Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(stream,config,resource,config.getSqlFragments()).parse();
            }
        }
        session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        // Derive isolated fixture columns from the unchanged production SELECT and actual entity types.
        fixtureTable(config,"cms_member_template","com.chengyinhub.business.mapper.CmsMemberTemplateMapper.selectCmsMemberTemplateById",CmsMemberTemplate.class);
        fixtureTable(config,"audit_task","com.chengyinhub.business.mapper.AuditTaskMapper.selectAuditTaskById",AuditTask.class);
        jdbc.update("insert into cms_member_template (id,member_id,title,description,question_name,question_answer,merchant_guide,validation_method,status,check_status,publish_status,draft_status,del_flag,original_template_id) values (42,8,'A riddle','Reviewed description','Question','Secret answer','Private staff guide',1,1,1,1,1,0,0)");
        jdbc.update("insert into audit_task (id,biz_type,biz_id,submitter_id,source_type,status,version,current_assignee) values (91,'TEMPLATE',42,8,'auto_gate',1,1,7)");
        refreshSnapshot(); reader=new ReviewedTemplatePackageSource(session,manager);
    }
    private void fixtureTable(Configuration config,String table,String statement,Class<?> entity) throws Exception {
        String sql=config.getMappedStatement(statement).getBoundSql(42L).getSql();
        String columns=sql.substring(sql.toLowerCase(Locale.ROOT).indexOf("select")+6,sql.toLowerCase(Locale.ROOT).indexOf("from"));
        List<String> definitions=new ArrayList<>();
        for(String raw:columns.split(",")) {
            String col=raw.trim(); StringBuilder property=new StringBuilder();
            for(String part:col.split("_"))property.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            Class<?> type=entity.getMethod("get"+property).getReturnType();
            String dbType=type==Long.class?"bigint":type==Integer.class?"int":type==Date.class?"timestamp":"varchar(1048576)";
            definitions.add(col+" "+dbType+("id".equals(col)?" primary key":""));
        }
        jdbc.execute("create table "+table+" ("+String.join(",",definitions)+")");
    }
    private void refreshSnapshot() {
        CmsMemberTemplate row=session.getMapper(CmsMemberTemplateMapper.class).selectCmsMemberTemplateById(42L);
        String snapshot=TemplateAuditSnapshot.personal(row); auditHash=sha(snapshot.getBytes(StandardCharsets.UTF_8));
        jdbc.update("update audit_task set snapshot_json=? where id=91",snapshot);
    }
    private ReviewedTemplatePackageSource.Evidence capture() { return reader.capture(42,91,1,auditHash); }
    private static String sha(byte[] bytes) {
        try {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void reason(String expected,org.junit.jupiter.api.function.Executable work) {
        assertEquals(expected,assertThrows(IllegalStateException.class,work).getMessage());
    }
    @Test void capturesActualApprovedSourceThroughExistingCodecAndManifestWithoutWriting() throws Exception {
        Map<String,Object> before=jdbc.queryForMap("select * from cms_member_template where id=42");
        ReviewedTemplatePackageSource.Evidence evidence=capture(); ModuleVersionSnapshot v=evidence.version();
        assertEquals(42,v.getSourceId()); assertEquals(8,v.getCreator().getEntityId());
        assertEquals("member-template:42",v.getModuleId());assertEquals("template-audit:91:v1",v.getVersionId());
        assertEquals(auditHash,v.getSourceRevision());assertEquals(auditHash,evidence.auditSnapshotHash());
        assertEquals(sha(evidence.reviewedText()),v.getContentHash());TextModuleDraftCodec.validateReviewedSource(evidence.reviewedText(),v);
        JsonNode content=JSON.readTree(evidence.reviewedText());
        assertEquals("w18-member-text-v1",content.path("schema").asText());assertEquals("Secret answer",content.path("questionAnswer").asText());
        assertEquals("Private staff guide",content.path("merchantGuide").asText());assertTrue(content.path("bindings").isObject());assertEquals(0,content.path("bindings").size());
        ModulePackageManifest manifest=evidence.manifestCandidate(terms(),sha(new byte[]{2}),1,2);
        assertEquals(Collections.singletonMap(v.getVersionId(),manifest.getManifestHash()),v.verifyPackageManifest(manifest,manifest.getManifestHash(),Collections.emptyList(),1,Collections.singleton(ModulePackageManifest.Capability.TEXT)));
        assertEquals(before,jdbc.queryForMap("select * from cms_member_template where id=42"));
        assertEquals(1,jdbc.queryForObject("select count(*) from audit_task",Integer.class));
        assertEquals(1,jdbc.queryForObject("select version from audit_task where id=91",Integer.class));
    }
    @Test void noCallerApprovalOwnerOrContentInputsAndNoBeanContentProjection() {
        Method[] methods=ReviewedTemplatePackageSource.class.getDeclaredMethods();
        Method capture=Arrays.stream(methods).filter(m->m.getName().equals("capture")).findFirst().get();
        assertArrayEquals(new Class[]{long.class,long.class,int.class,String.class},capture.getParameterTypes());
        for(Method method:ReviewedTemplatePackageSource.Evidence.class.getDeclaredMethods())assertFalse(method.getName().startsWith("get"));
        assertEquals(0,ReviewedTemplatePackageSource.Evidence.class.getConstructors().length);
    }
    @Test void wrongOwnerTemplateKindOrReviewSourceCannotSupplyApproval() {
        for(String assignment:Arrays.asList("submitter_id=9","biz_id=43","biz_type='TEMPLATE_LIBRARY'","source_type='report'")) {
            jdbc.update("update audit_task set "+assignment+" where id=91");
            reason("SOURCE_REVIEW_MISMATCH",this::capture);
            jdbc.update("update audit_task set submitter_id=8,biz_id=42,biz_type='TEMPLATE',source_type='auto_gate' where id=91");
        }
    }
    @Test void missingSourceOrAuditFailsClosed() {
        reason("SOURCE_UNAVAILABLE",()->reader.capture(43,91,1,auditHash));
        reason("SOURCE_REVIEW_MISMATCH",()->reader.capture(42,92,1,auditHash));
    }
    @Test void callerHashOrVersionCannotBlessAChangedSource() {
        reason("SOURCE_HASH_CHANGED",()->reader.capture(42,91,1,sha(new byte[]{9})));
        reason("SOURCE_NOT_CURRENTLY_APPROVED",()->reader.capture(42,91,2,auditHash));
        jdbc.update("update cms_member_template set title='Changed after review' where id=42");
        reason("SOURCE_CONTENT_CHANGED",this::capture);
    }
    @Test void allApprovalAndLifecycleStatesMustStillMatch() {
        for(String field:Arrays.asList("status","check_status","publish_status","draft_status")) {
            jdbc.update("update cms_member_template set "+field+"=0 where id=42");
            reason("SOURCE_NOT_CURRENTLY_APPROVED",this::capture);
            jdbc.update("update cms_member_template set "+field+"=1 where id=42");
        }
        jdbc.update("update cms_member_template set del_flag=2 where id=42");reason("SOURCE_NOT_CURRENTLY_APPROVED",this::capture);
        jdbc.update("update cms_member_template set del_flag=0 where id=42");
        for(int state:new int[]{0,2,3,4}) {jdbc.update("update audit_task set status=? where id=91",state);reason("SOURCE_NOT_CURRENTLY_APPROVED",this::capture);}
    }
    @Test void duplicateNestedTrailingAndOversizedReviewSnapshotsRejected() {
        String normal=jdbc.queryForObject("select snapshot_json from audit_task where id=91",String.class);
        for(String invalid:Arrays.asList(normal.substring(0,normal.length()-1)+",\"title\":\"A riddle\"}","{\"x\":{}}",normal+" {}",new String(new char[262145]).replace('\0',' '))) {
            jdbc.update("update audit_task set snapshot_json=? where id=91",invalid);
            reason("INVALID_REVIEW_SNAPSHOT",this::capture);
        }
    }
    @Test void editedAuditJsonWithSameCallerHashIsNotAccepted() {
        jdbc.update("update audit_task set snapshot_json='{}' where id=91");reason("SOURCE_HASH_CHANGED",this::capture);
        auditHash=sha("{}".getBytes(StandardCharsets.UTF_8));reason("SOURCE_CONTENT_CHANGED",this::capture);
    }
    @Test void importedOrRemixedTemplateCannotImplyRedistributionRights() {
        jdbc.update("update cms_member_template set original_template_id=55 where id=42");refreshSnapshot();
        reason("UNSUPPORTED_SOURCE_FIELD:originalTemplateId",this::capture);
    }
    @Test void nonTextMediaRewardsAndExecutableConfigurationAreNotSilentlyDropped() {
        for(String field:Arrays.asList("img_url","reward_rules_json","advanced_config_json","question_a","interaction_type")) {
            jdbc.update("update cms_member_template set "+field+"='unsupported' where id=42");refreshSnapshot();
            assertTrue(assertThrows(IllegalStateException.class,this::capture).getMessage().startsWith("UNSUPPORTED_SOURCE_FIELD:"));
            jdbc.update("update cms_member_template set "+field+"=null where id=42");
        }
        jdbc.update("update cms_member_template set validation_method=3 where id=42");refreshSnapshot();reason("UNSUPPORTED_SOURCE_KIND",this::capture);
    }
    @Test void venueMaterialsPlayerAndDifficultyRequirementsCannotBecomePortableText() {
        for(String field:Arrays.asList("usage_location","required_materials","players","difficulty")) {
            jdbc.update("update cms_member_template set "+field+"='Required constraint' where id=42");refreshSnapshot();
            assertTrue(assertThrows(IllegalStateException.class,this::capture).getMessage().startsWith("UNSUPPORTED_SOURCE_FIELD:"));
            jdbc.update("update cms_member_template set "+field+"=null where id=42");
        }
        jdbc.update("update cms_member_template set duration=30 where id=42");refreshSnapshot();
        reason("UNSUPPORTED_SOURCE_FIELD:duration",this::capture);
    }
    @Test void actualCouponXpOrMissingAnswerCannotBeExportedAsPlainText() {
        jdbc.update("update cms_member_template set coupon_id=5 where id=42");refreshSnapshot();reason("UNSUPPORTED_SOURCE_FIELD:couponId",this::capture);
        jdbc.update("update cms_member_template set coupon_id=0,xp_value=10 where id=42");refreshSnapshot();reason("UNSUPPORTED_SOURCE_FIELD:xpValue",this::capture);
        jdbc.update("update cms_member_template set xp_value=0,question_answer='' where id=42");refreshSnapshot();reason("INCOMPLETE_TEXT_SOURCE",this::capture);
    }
    @Test void oldEvidenceIsImmutableAndNewCaptureRechecksRevocation() {
        ReviewedTemplatePackageSource.Evidence old=capture();byte[] bytes=old.reviewedText();bytes[0]^=1;
        assertEquals(old.version().getContentHash(),sha(old.reviewedText()));
        jdbc.update("update audit_task set status=2,version=2 where id=91");reason("SOURCE_NOT_CURRENTLY_APPROVED",this::capture);
        assertEquals(old.version().getContentHash(),sha(old.reviewedText()));
    }
    @Test void differentDatasourceAndLaterConfigurationSwapFailClosed() {
        DriverManagerDataSource other=new DriverManagerDataSource("jdbc:h2:mem:wrong_"+System.nanoTime(),"sa","");
        reason("SOURCE_DATASOURCE_MISMATCH",()->new ReviewedTemplatePackageSource(session,new DataSourceTransactionManager(other)));
        manager.setDataSource(other);reason("SOURCE_DATASOURCE_MISMATCH",this::capture);
    }
    @Test void staleEditCommittedWhileReaderWaitsOnTemplateLockIsRejected() throws Exception {
        assertConcurrentChangeRejected("update cms_member_template set title='Concurrent edit' where id=42","SOURCE_CONTENT_CHANGED");
    }
    @Test void auditRevocationCommittedWhileReaderWaitsOnAuditLockIsRejected() throws Exception {
        assertConcurrentChangeRejected("update audit_task set status=2,version=2 where id=91","SOURCE_NOT_CURRENTLY_APPROVED");
    }
    private void assertConcurrentChangeRejected(String update,String expected) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch locked=new CountDownLatch(1),commit=new CountDownLatch(1),reading=new CountDownLatch(1);
        try {
            Future<?> writer=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                jdbc.update(update);locked.countDown();try {if(!commit.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test deadline");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            Future<?> read=pool.submit(()->{reading.countDown();return capture();});assertTrue(reading.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->read.get(150,TimeUnit.MILLISECONDS));
            commit.countDown();writer.get(5,TimeUnit.SECONDS);
            ExecutionException failure=assertThrows(ExecutionException.class,()->read.get(5,TimeUnit.SECONDS));
            assertEquals(expected,failure.getCause().getMessage());
        } finally {commit.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private static LicenseTermsSnapshot terms() {
        byte[] document="Explicit reviewed rights; no expiry is supplied here".getBytes(StandardCharsets.UTF_8);
        return new LicenseTermsSnapshot("v1",document,sha(document),"reviewed-terms",LicenseTermsSnapshot.Permission.PROHIBITED,
                LicenseTermsSnapshot.Adaptation.BIND_RESOURCES_ONLY,LicenseTermsSnapshot.Permission.PROHIBITED,
                LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION,LicenseTermsSnapshot.Redistribution.PROHIBITED,
                Collections.singleton("CN"),LicenseTermsSnapshot.Limit.atMost(1),LicenseTermsSnapshot.Limit.atMost(0),LicenseTermsSnapshot.Limit.unlimited());
    }
}
