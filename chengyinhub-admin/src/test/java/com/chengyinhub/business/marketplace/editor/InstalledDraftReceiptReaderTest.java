package com.chengyinhub.business.marketplace.editor;

import com.chengyinhub.business.marketplace.editor.store.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.plugin.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.junit.jupiter.api.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SELECT mapper against disposable H2 schema; no content-use authority is mocked into existence. */
class InstalledDraftReceiptReaderTest {
    protected static final String HASH=repeat('a'), CONTENT=repeat('b'), TERMS=repeat('c');
    protected JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    protected SqlSessionTemplate session;
    protected PersistedInstalledDraftReceiptReader reader;
    private final ReadTransactionProbe probe=new ReadTransactionProbe();
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    private static final class ReadTransactionProbe implements Interceptor {
        boolean readOnly=true,repeatable=true,active=true; int queries;
        Runnable afterTargetRead;
        public Object intercept(Invocation invocation) throws Throwable {
            queries++;readOnly &= TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            active &= TransactionSynchronizationManager.isActualTransactionActive();
            repeatable &= Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            Object result=invocation.proceed();
            if (((MappedStatement)invocation.getArgs()[0]).getId().endsWith(".findOwnedTarget") && afterTargetRead!=null) {
                Runnable hook=afterTargetRead;afterTargetRead=null;hook.run();
            }
            return result;
        }
        public Object plugin(Object target){return Plugin.wrap(target,this);}
        public void setProperties(Properties properties){}
    }
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds=createDataSource();
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        applySchema(jdbc);
        initializeFixture();
    }
    protected DriverManagerDataSource createDataSource() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:receipt_"+System.nanoTime()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        ds.setDriverClassName("org.h2.Driver");return ds;
    }
    protected void applySchema(JdbcTemplate jdbc) throws Exception {
        for(String migration:Arrays.asList("content_draft_im_poll_20260828","w18_module_install_reservation_20261002","w18_owned_text_draft_install_20261002","w18_content_draft_install_scope_20261003")) {
            Path path=Paths.get("sql/migration_"+migration+".sql");if(!Files.exists(path))path=Paths.get("chengyinhub-admin").resolve(path);
            String sql=new String(Files.readAllBytes(path),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","")
                .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4(?: COLLATE=utf8mb4_bin)?(?: COMMENT='[^']*')?","");
            for(String part:sql.split(";"))if(!part.trim().isEmpty()&&!part.trim().startsWith("SELECT"))jdbc.execute(part);
        }
        jdbc.execute("CREATE INDEX idx_w18_owned_receipt_read ON w18_module_owned_draft(buyer_kind,buyer_id,target_draft_id,installed_at,id)");
    }
    private void initializeFixture() throws Exception {
        jdbc.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY,status INT,del_flag INT)");
        jdbc.update("INSERT INTO ums_member VALUES(42,1,0),(43,1,0)");
        jdbc.update("INSERT INTO content_draft(id,owner_member_id,business_type,client_draft_key,payload_json,payload_hash,status,version,updated_by_device) VALUES(91,42,'TOPIC','topic','{}',?,'DRAFT',1,'fixture'),(92,43,'ACTIVITY','activity','{}',?,'DRAFT',1,'fixture')",HASH,HASH);
        jdbc.update("INSERT INTO w18_content_draft_install_scope VALUES(91,1,42,?,'explicit-region'),(92,1,43,?,'explicit-region')",HASH,HASH);
        Configuration configuration=new Configuration();configuration.setEnvironment(new Environment("receipt",new SpringManagedTransactionFactory(),manager.getDataSource()));
        String path="mapper/marketplace/InstalledDraftReceiptStore.xml";
        try(InputStream in=Resources.getResourceAsStream(path)){new XMLMapperBuilder(in,configuration,path,configuration.getSqlFragments()).parse();}
        configuration.addInterceptor(probe);
        session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));reader=new PersistedInstalledDraftReceiptReader(session,manager);
        seed(1,42,91,1);seed(2,43,92,1);
    }
    protected void seed(long id,long buyer,long draft,long revision) {
        String key=String.format(Locale.ROOT,"%064d",id);
        Timestamp at=Timestamp.from(Instant.parse("2026-10-03T00:00:00Z").plusSeconds(id));
        jdbc.update("INSERT INTO w18_module_install_reservation(reservation_key,buyer_kind,buyer_id,request_id,license_key,license_id,payload_hash,authority_hash,module_id,version_id,content_hash,offer_version,terms_hash,seller_kind,seller_id,source_event_id,grant_evidence_reference,approval_reference,draft_id,draft_revision,actor_id,state,reserved_at,expires_at,terminal_at) VALUES(?,'INDIVIDUAL',?,'private-request',?,'private-license',?,?,'private-module','version-1',?,'offer-1',?,'INDIVIDUAL',8,'private-event','private-evidence','private-review',?,?,?,'CONSUMED',?,?,?)",key,buyer,HASH,HASH,HASH,CONTENT,TERMS,draft,revision,buyer,at,at,at);
        jdbc.update("INSERT INTO w18_module_owned_draft(id,reservation_key,buyer_kind,buyer_id,target_draft_id,target_revision,revision,state,review_status,publish_status,is_sync,source_content,terms_document,frozen_rights,installed_by,installed_at) VALUES(?,?,'INDIVIDUAL',?,?,?,1,'DRAFT',0,0,0,?,?,?, ?,?)",id,key,buyer,draft,revision,"<script>private source</script>".getBytes(StandardCharsets.UTF_8),"private terms".getBytes(StandardCharsets.UTF_8),"{\"private\":\"secret\"}",buyer,at);
        jdbc.update("INSERT INTO w18_module_draft_component VALUES(?,'template','TEXT_TEMPLATE',1,'{\"title\":\"private-title\",\"url\":\"https://invalid.example/private\"}')",id);
        jdbc.update("INSERT INTO w18_module_install_usage VALUES(?,?,?)",key,id,at);
    }
    protected InstalledDraftReceipts read(){return reader.read(42,91,1,HASH);}
    private InstalledDraftReceipts.Receipt receipt(){return read().getReceipts().get(0);}
    private static String repeat(char c){char[] s=new char[64];Arrays.fill(s,c);return new String(s);}
    @Test void exactTopicReceiptContainsOnlyHistoricalBoundIdentity() {
        InstalledDraftReceipts out=read();assertEquals(1,out.getReceipts().size());
        InstalledDraftReceipts.Receipt r=out.getReceipts().get(0);
        assertEquals(1,r.getInstallationId());assertEquals(91,r.getTargetDraftId());assertEquals(1,r.getInstalledTargetVersion());
        assertEquals(HASH,r.getInstalledTargetPayloadHash());assertEquals(CONTENT,r.getContentHash());assertEquals(TERMS,r.getTermsHash());
        assertEquals(InstalledDraftReceipts.Binding.EXACT_REVISION,r.getBinding());
        assertEquals(InstalledDraftReceipts.Availability.HISTORICAL_RECEIPTS_ONLY,out.getAvailability());
        assertThrows(UnsupportedOperationException.class,()->out.getReceipts().clear());
    }
    @Test void activityDestinationIsSupportedWithoutChangingSourceKind(){assertEquals(2,reader.read(43,92,1,HASH).getReceipts().get(0).getInstallationId());}
    @Test void everyContentUseRemainsDeniedEvenForExactInstall(){
        InstalledDraftReceipts out=read();assertEquals("POST_INSTALL_POLICY_UNAVAILABLE",out.getContentUseStatus());
        assertFalse(out.isTextPreviewAllowed());assertFalse(out.isEditingAllowed());assertFalse(out.isExportAllowed());
        assertFalse(out.isPublicationAllowed());assertFalse(out.isCommercialUseAllowed());assertFalse(out.isExecutionAllowed());
    }
    @Test void expiredReservationStillYieldsOnlyHistoricalReceipt(){assertNotNull(receipt());assertFalse(read().isTextPreviewAllowed());}
    @Test void noGrantOrPublicationLookupCanReinterpretWithdrawnOrRevokedRights(){
        // No registry/publication tables exist in this fixture. Historical receipts remain readable;
        // an ACTIVE, RETIRED, EXPIRED, REVOKED or emergency-blocked grant could not enable content use.
        assertEquals("HISTORICAL_INSTALLATION",receipt().getEvidenceKind());assertFalse(read().isTextPreviewAllowed());
    }
    @Test void otherOwnerCannotReadReceipt(){assertEquals(404,assertThrows(ServiceException.class,()->reader.read(43,91,1,HASH)).getCode());}
    @Test void unauthenticatedCannotRead(){assertEquals(401,assertThrows(ServiceException.class,()->reader.read(0,91,1,HASH)).getCode());}
    @Test void inactiveMemberCannotRead(){jdbc.update("UPDATE ums_member SET status=0 WHERE id=42");assertEquals(404,assertThrows(ServiceException.class,this::read).getCode());}
    @Test void deletedMemberCannotRead(){jdbc.update("UPDATE ums_member SET del_flag=1 WHERE id=42");assertEquals(404,assertThrows(ServiceException.class,this::read).getCode());}
    @Test void endedDraftCannotRead(){jdbc.update("UPDATE content_draft SET status='PUBLISHED' WHERE id=91");assertEquals(404,assertThrows(ServiceException.class,this::read).getCode());}
    @Test void staleRestoreSnapshotHasExplicitConflict(){jdbc.update("UPDATE content_draft SET version=2 WHERE id=91");ServiceException e=assertThrows(ServiceException.class,this::read);assertEquals(409,e.getCode());assertTrue(e.getMessage().startsWith("DRAFT_CHANGED"));}
    @Test void editedVersionKeepsReceiptButCannotSilentlyReattach(){jdbc.update("UPDATE content_draft SET version=2 WHERE id=91");assertEquals(InstalledDraftReceipts.Binding.STALE_BINDING,reader.read(42,91,2,HASH).getReceipts().get(0).getBinding());}
    @Test void editedPayloadAtSameVersionAlsoMarksStale(){jdbc.update("UPDATE content_draft SET payload_hash=? WHERE id=91",repeat('d'));assertEquals(InstalledDraftReceipts.Binding.STALE_BINDING,reader.read(42,91,1,repeat('d')).getReceipts().get(0).getBinding());}
    @Test void missingScopeCannotInventPayloadBinding(){jdbc.update("DELETE FROM w18_content_draft_install_scope WHERE draft_id=91");assertEquals(InstalledDraftReceipts.Binding.BINDING_UNVERIFIABLE,receipt().getBinding());assertNull(receipt().getInstalledTargetPayloadHash());}
    @Test void wrongScopeOwnerCannotExposeOtherOwnerHash(){jdbc.update("UPDATE w18_content_draft_install_scope SET owner_member_id=43 WHERE draft_id=91");assertEquals(InstalledDraftReceipts.Binding.BINDING_UNVERIFIABLE,receipt().getBinding());}
    @Test void mismatchedReservationTargetCannotBecomeReceipt(){jdbc.update("UPDATE w18_module_install_reservation SET draft_id=92 WHERE buyer_id=42");assertTrue(read().getReceipts().isEmpty());}
    @Test void mismatchedReservationBuyerCannotBecomeReceipt(){jdbc.update("UPDATE w18_module_install_reservation SET buyer_id=43 WHERE buyer_id=42");assertTrue(read().getReceipts().isEmpty());}
    @Test void unconsumedReservationIsNotHistoricalInstallation(){jdbc.update("UPDATE w18_module_install_reservation SET state='RESERVED' WHERE buyer_id=42");assertTrue(read().getReceipts().isEmpty());}
    @Test void mismatchedUsageTimestampCannotBecomeReceipt(){jdbc.update("UPDATE w18_module_install_usage SET committed_at=?",Timestamp.from(Instant.parse("2026-10-04T00:00:00Z")));assertTrue(read().getReceipts().isEmpty());}
    @Test void wrongComponentKindIsExcluded(){jdbc.update("UPDATE w18_module_draft_component SET component_kind='EXECUTABLE' WHERE draft_id=1");assertTrue(read().getReceipts().isEmpty());}
    @Test void corruptedHashFailsClosed(){jdbc.update("UPDATE w18_module_install_reservation SET content_hash='bad' WHERE buyer_id=42");assertEquals("INSTALLATION_RECEIPT_INVALID",assertThrows(ServiceException.class,this::read).getMessage());}
    @Test void oversizedVersionIdentifierFailsClosedWithoutLoadingText(){jdbc.update("UPDATE w18_module_install_reservation SET version_id=? WHERE buyer_id=42",String.join("",Collections.nCopies(513,"a")));assertThrows(ServiceException.class,this::read);}
    @Test void sourceAndRightsPrivateFieldsCannotSerialize() throws Exception {
        String json=new ObjectMapper().writeValueAsString(read());
        for(String forbidden:Arrays.asList("private","<script>","source_content","sourceContent","contentJson","frozenRights","termsDocument","licenseId","sellerId","approvalReference","https://"))assertFalse(json.contains(forbidden),forbidden);
    }
    @Test void sourceAndRightsCorruptionCannotForceProtectedByteRead(){jdbc.update("UPDATE w18_module_owned_draft SET source_content=?,terms_document=?,frozen_rights='malformed'",new byte[]{0},new byte[]{0});assertEquals(1,read().getReceipts().size());assertFalse(read().isTextPreviewAllowed());}
    @Test void resultsAreBoundedAndDeterministicallyOrdered(){for(long n=3;n<=54;n++)seed(n,42,91,1);InstalledDraftReceipts out=read();assertEquals(50,out.getReceipts().size());assertTrue(out.isHasMore());assertEquals(54,out.getReceipts().get(0).getInstallationId());assertEquals(5,out.getReceipts().get(49).getInstallationId());}
    @Test void emptyOwnedDraftReturnsAnExplicitEmptyReceiptProjection(){jdbc.update("DELETE FROM w18_module_install_usage WHERE owned_draft_id=1");assertTrue(read().getReceipts().isEmpty());assertFalse(read().isHasMore());}
    @Test void readDoesNotMutateAnyInstallationOrDraftRow(){List<Map<String,Object>> before=jdbc.queryForList("SELECT id,status,version,payload_hash FROM content_draft");read();read();assertEquals(before,jdbc.queryForList("SELECT id,status,version,payload_hash FROM content_draft"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM w18_module_owned_draft",Integer.class));}
    @Test void mismatchedDatasourceRejectedAtConstruction(){DriverManagerDataSource alias=new DriverManagerDataSource(((DriverManagerDataSource)manager.getDataSource()).getUrl(),"sa","");assertThrows(IllegalStateException.class,()->new PersistedInstalledDraftReceiptReader(session,new DataSourceTransactionManager(alias)));}
    @Test void datasourceReconfigurationRejectedBeforeRead(){manager.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:unrelated","sa",""));assertThrows(IllegalStateException.class,this::read);}
    @Test void invalidTargetBindingRejectedBeforeQuery(){assertEquals(400,assertThrows(ServiceException.class,()->reader.read(42,91,1,"wrong")).getCode());}
    @Test void transferredDraftDoesNotTransferOriginalBuyerReceipts(){jdbc.update("UPDATE content_draft SET owner_member_id=43 WHERE id=91");assertTrue(reader.read(43,91,1,HASH).getReceipts().isEmpty());assertEquals(404,assertThrows(ServiceException.class,this::read).getCode());}
    @Test void merchantOrOrganizationBuyerIsNeverProjectedAsIndividual(){jdbc.update("UPDATE w18_module_owned_draft SET buyer_kind='MERCHANT' WHERE id=1");jdbc.update("UPDATE w18_module_install_reservation SET buyer_kind='MERCHANT' WHERE buyer_id=42");assertTrue(read().getReceipts().isEmpty());}
    @Test void otherInstallerCannotLaunderReceiptThroughOwnedTarget(){jdbc.update("UPDATE w18_module_owned_draft SET installed_by=43 WHERE id=1");assertTrue(read().getReceipts().isEmpty());}
    @Test void malformedBindingHashRemainsUnverifiable(){jdbc.update("UPDATE w18_content_draft_install_scope SET payload_hash='bad' WHERE draft_id=91");assertEquals(InstalledDraftReceipts.Binding.BINDING_UNVERIFIABLE,receipt().getBinding());}
    @Test void absentTextComponentDoesNotPretendInstallationIsRenderable(){jdbc.update("DELETE FROM w18_module_draft_component WHERE draft_id=1");assertTrue(read().getReceipts().isEmpty());assertFalse(read().isTextPreviewAllowed());}
    @Test void everySelectUsesAnActualReadOnlyRepeatableSnapshot(){read();assertEquals(2,probe.queries);assertTrue(probe.active);assertTrue(probe.readOnly);assertTrue(probe.repeatable);}

    protected void commitBetweenQueries(Runnable write) {
        probe.afterTargetRead=()->{
            TransactionTemplate concurrent=new TransactionTemplate(manager);
            concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            concurrent.execute(status->{write.run();return null;});
        };
    }
    @Test void draftCasCommittedBetweenSelectsCannotMixVersions() {
        commitBetweenQueries(()->assertEquals(1,jdbc.update("UPDATE content_draft SET version=2,payload_hash=? WHERE id=91 AND owner_member_id=42 AND version=1",repeat('d'))));
        InstalledDraftReceipts out=read();
        assertEquals(InstalledDraftReceipts.Binding.EXACT_REVISION,out.getReceipts().get(0).getBinding());
        assertEquals(HASH,out.getReceipts().get(0).getInstalledTargetPayloadHash());
        assertEquals(409,assertThrows(ServiceException.class,this::read).getCode());
        assertEquals(InstalledDraftReceipts.Binding.STALE_BINDING,reader.read(42,91,2,repeat('d')).getReceipts().get(0).getBinding());
    }
    @Test void tiedInstallTimesAreOrderedByDescendingInstallationId() {
        seed(3,42,91,1);seed(4,42,91,1);
        Timestamp time=Timestamp.from(Instant.parse("2026-10-03T00:00:00.123456Z"));
        jdbc.update("UPDATE w18_module_install_usage SET committed_at=?",time);
        jdbc.update("UPDATE w18_module_owned_draft SET installed_at=?",time);
        jdbc.update("UPDATE w18_module_install_reservation SET terminal_at=?",time);
        List<InstalledDraftReceipts.Receipt> receipts=read().getReceipts();
        assertEquals(Arrays.asList(4L,3L,1L),Arrays.asList(receipts.get(0).getInstallationId(),receipts.get(1).getInstallationId(),receipts.get(2).getInstallationId()));
        assertEquals("2026-10-03T00:00:00.123456Z",receipts.get(0).getInstalledAt());
    }
    @Test void exactlyFiftyReceiptsDoesNotReportAnExtraPage() {
        for(long n=3;n<=51;n++)seed(n,42,91,1);
        assertEquals(50,read().getReceipts().size());assertFalse(read().isHasMore());
    }
    @Test void serializerEmitsEveryUnavailableCapabilityAsBooleanFalse() {
        for(InstalledDraftReceipts projection:Arrays.asList(read(),
                InstalledDraftReceipts.unavailable(InstalledDraftReceipts.Availability.NOT_ENABLED),
                InstalledDraftReceipts.unavailable(InstalledDraftReceipts.Availability.OWNER_ONLY))) {
            com.fasterxml.jackson.databind.JsonNode json=new ObjectMapper().valueToTree(projection);
            for(String key:Arrays.asList("textPreviewAllowed","editingAllowed","exportAllowed","publicationAllowed","executionAllowed","commercialUseAllowed")) {
                assertTrue(json.has(key),key);assertTrue(json.get(key).isBoolean(),key);assertFalse(json.get(key).booleanValue(),key);
            }
            assertEquals(10,json.size());
        }
    }
    @Test void receiptSerializationUsesOnlyTheExplicitMetadataAllowlist() {
        com.fasterxml.jackson.databind.JsonNode json=new ObjectMapper().valueToTree(receipt());
        Set<String> actual=new TreeSet<>();json.fieldNames().forEachRemaining(actual::add);
        assertEquals(new TreeSet<>(Arrays.asList("installationId","targetDraftId","installedTargetVersion","installedAt","versionId","contentHash","termsHash","installedTargetPayloadHash","binding","evidenceKind")),actual);
    }
    @Test void nonSpringTransactionFactoryCannotPretendReadsAreEnlisted() {
        session.getSqlSessionFactory().getConfiguration().setEnvironment(new Environment("unsafe",new JdbcTransactionFactory(),manager.getDataSource()));
        assertThrows(IllegalStateException.class,this::read);
    }

}
