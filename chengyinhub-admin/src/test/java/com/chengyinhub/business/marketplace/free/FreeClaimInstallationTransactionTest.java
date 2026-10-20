package com.chengyinhub.business.marketplace.free;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.reservation.*;
import com.chengyinhub.business.marketplace.reservation.store.*;
import com.chengyinhub.business.marketplace.free.store.*;
import com.chengyinhub.business.domain.ContentDraft;
import com.chengyinhub.business.mapper.ContentDraftMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Real free registry, entitlement, target, reservation and usage mappers. Only reviewed ingress is seeded. */
class FreeClaimInstallationTransactionTest {
    protected static final Instant NOW=Instant.parse("2026-10-03T00:00:00Z");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private FreeModuleClaimStore store;
    private FreeModuleClaimService service;
    private FreePublicationSnapshot snapshot;
    private String digest;
    @BeforeEach void setupFreeRegistry() throws Exception {
        DriverManagerDataSource ds=createDataSource();jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        applySchema(jdbc);
        jdbc.execute("create table ums_member(id bigint primary key,status int not null,del_flag int not null)");
        jdbc.update("insert into ums_member values(42,1,0),(43,1,0),(8,1,0)");
        Configuration c=new Configuration();c.setEnvironment(new Environment("free-claim",new SpringManagedTransactionFactory(),ds));
        String path="mapper/marketplace/FreeModuleClaimStore.xml";
        try(InputStream in=Resources.getResourceAsStream(path)){new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}
        store=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c)).getMapper(FreeModuleClaimStore.class);
        service=new FreeModuleClaimService(store,manager,Clock.fixed(NOW,ZoneOffset.UTC));
        byte[] content="{\"schema\":\"w18-member-text-v1\",\"title\":\"Reviewed\",\"bindings\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] terms="Explicit reviewed fixture rights; no default grant".getBytes(StandardCharsets.UTF_8);
        BuyerPrincipal seller=new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,8);
        LicenseTermsSnapshot policy=new LicenseTermsSnapshot("terms-1",terms,FreePublicationSnapshot.sha(terms),"reviewed-terms:1",
                LicenseTermsSnapshot.Permission.PROHIBITED,LicenseTermsSnapshot.Adaptation.BIND_RESOURCES_ONLY,
                LicenseTermsSnapshot.Permission.PROHIBITED,LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION,
                LicenseTermsSnapshot.Redistribution.PROHIBITED,Collections.singleton("region-1"),
                LicenseTermsSnapshot.Limit.atMost(1),LicenseTermsSnapshot.Limit.atMost(0),LicenseTermsSnapshot.Limit.atMost(3));
        ModuleVersionSnapshot version=new ModuleVersionSnapshot(seller,"module-1","version-1","1",1,ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE,
                11,"revision-1",content,FreePublicationSnapshot.sha(content),"fixture-review:1",Collections.emptyMap());
        LicenseOfferSnapshot offer=new LicenseOfferSnapshot("offer-1","offer-v1","module-1",seller,Collections.singleton(BuyerPrincipal.Kind.INDIVIDUAL),
                LicenseOfferSnapshot.Acquisition.FREE,0,"CNY",policy);
        snapshot=new FreePublicationSnapshot(version,offer,content,terms,NOW.plusSeconds(3600));
        digest=FreePublicationSnapshot.sha(snapshot.encode().getBytes(StandardCharsets.UTF_8));seed("publication-1");installationSetup();
    }
    protected DriverManagerDataSource createDataSource() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:free_"+System.nanoTime()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        ds.setDriverClassName("org.h2.Driver");return ds;
    }
    private void seed(String id){
        jdbc.update("insert into w18_free_publication values(?,?,?,?,?,?,?,?,?)",id,snapshot.ownershipKey(),8,11,digest,snapshot.encode(),snapshot.getContent(),snapshot.getTerms(),Timestamp.from(NOW.plusSeconds(3600)));
        jdbc.update("insert into w18_free_publication_policy values(?,'LISTED')",id);
    }
    private SqlSessionTemplate session;
    private final StorageProbe probe=new StorageProbe();
    private interface SqlHook { void run(String statement) throws Exception; }
    @Intercepts(@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}))
    private static final class StorageProbe implements Interceptor {
        volatile SqlHook before=id->{},after=id->{};
        public Object intercept(Invocation call) throws Throwable {
            String id=((MappedStatement)call.getArgs()[0]).getId();before.run(id);
            Object result=call.proceed();after.run(id);return result;
        }
        public Object plugin(Object target){return Plugin.wrap(target,this);}
        public void setProperties(Properties properties) { }
    }
    private FreeClaimInstallationAuthority authority;
    private FreeContentDraftScopeService scopes;
    private FreeModuleLifecycleService lifecycle;
    private ModuleReservationStore reservations;
    private ModuleInstallationStore installs;
    private ModuleInstallReservationService reserve;
    private ModuleTextDraftInstallationService installer;
    private ContentDraftMapper drafts;
    private String claimId;
    private final AtomicReference<Instant> time=new AtomicReference<Instant>(NOW);
    private final Clock clock=new Clock(){public Instant instant(){return time.get();}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}};
    protected void applySchema(JdbcTemplate db) throws Exception {
        applyRuntime(db,"w18_free_claim_registry_20261003");
        applyRuntime(db,"content_draft_im_poll_20260828");
        applyRuntime(db,"w18_module_install_reservation_20261002");
        applyRuntime(db,"w18_owned_text_draft_install_20261002");
        applyRuntime(db,"w18_content_draft_install_scope_20261003");
    }
    private void applyRuntime(JdbcTemplate db,String name) throws Exception {
        Path file=Paths.get("sql/migration_"+name+".sql");if(!Files.exists(file))file=Paths.get("chengyinhub-admin").resolve(file);
        String sql=new String(Files.readAllBytes(file),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","")
                .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4(?: COLLATE=utf8mb4_bin)?(?: COMMENT='[^']*')?","");
        for(String part:sql.split(";"))if(!part.trim().isEmpty()&&!part.trim().startsWith("SELECT"))db.execute(part);
    }
    private void installationSetup() throws Exception {
        Configuration configuration=new Configuration();configuration.getTypeAliasRegistry().registerAlias("ContentDraft",ContentDraft.class);
        configuration.setEnvironment(new Environment("persisted-free-install",new SpringManagedTransactionFactory(),manager.getDataSource()));
        for(String mapper:Arrays.asList("marketplace/FreeModuleClaimStore","marketplace/FreeInstallTargetStore","marketplace/ModuleReservationStore","marketplace/ModuleInstallationStore","business/ContentDraftMapper")) {
            String path="mapper/"+mapper+".xml";try(InputStream input=Resources.getResourceAsStream(path)){new XMLMapperBuilder(input,configuration,path,configuration.getSqlFragments()).parse();}
        }
        configuration.addInterceptor(probe);
        session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        authority=new FreeClaimInstallationAuthority(session,manager,clock);scopes=new FreeContentDraftScopeService(session,manager);lifecycle=new FreeModuleLifecycleService(session,manager);
        reservations=session.getMapper(ModuleReservationStore.class);installs=session.getMapper(ModuleInstallationStore.class);drafts=session.getMapper(ContentDraftMapper.class);
        reserve=authority.reservationService();installer=authority.installationService();
        for(long id:Arrays.asList(91L,92L))jdbc.update("INSERT INTO content_draft(id,owner_member_id,business_type,client_draft_key,payload_json,payload_hash,status,version,updated_by_device) VALUES(?,42,'TOPIC',?,'{}',?,'DRAFT',1,'test')",id,"draft-"+id,FreePublicationSnapshot.sha("{}".getBytes(StandardCharsets.UTF_8)));
        scopes.prepare(42,91,1,"region-1");scopes.prepare(42,92,1,"region-1");
        claimId=service.claim(42,"publication-1",digest,"install-license").getClaimId();
    }
    private ModuleInstallRequest request(String key,long target){return request(key,target,42,claimId,false,Collections.emptySet());}
    private ModuleInstallRequest request(String key,long target,long actor,String grant,boolean commercial,Set<ModuleInstallRequest.Change> changes) {
        return new ModuleInstallRequest(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,actor),key,grant,"module-1","version-1",snapshot.getVersion().getContentHash(),"offer-v1",snapshot.getOffer().getTerms().getTermsHash(),target,1,commercial,changes,Collections.emptyMap());
    }
    private Instant until(){return NOW.plusSeconds(600);}
    private void hold(ModuleInstallRequest request){reserve.reserve(42,request,until());}
    private ModuleInstallationReceipt consume(ModuleInstallRequest request){return installer.consume(42,request,until());}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private static void denied(String reason,Runnable call){assertEquals(reason,assertThrows(IllegalStateException.class,call::run).getMessage());}

    @Test void persistedClaimReservesAndConsumesExactBytesOnRealEditorDraft(){
        ModuleInstallRequest request=request("one",91);hold(request);ModuleInstallationReceipt receipt=consume(request);
        assertNotNull(receipt);assertEquals(1,count("w18_module_owned_draft"));assertEquals(1,count("w18_module_install_usage"));
        assertArrayEquals(snapshot.getContent(),jdbc.queryForObject("SELECT source_content FROM w18_module_owned_draft",byte[].class));
        assertArrayEquals(snapshot.getTerms(),jdbc.queryForObject("SELECT terms_document FROM w18_module_owned_draft",byte[].class));
        assertEquals(91L,jdbc.queryForObject("SELECT target_draft_id FROM w18_module_owned_draft",Long.class));
        assertEquals("DRAFT",jdbc.queryForObject("SELECT state FROM w18_module_owned_draft",String.class));
        assertEquals(1L,jdbc.queryForObject("SELECT version FROM content_draft WHERE id=91",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT publish_status+is_sync+review_status FROM w18_module_owned_draft",Integer.class));
    }
    @Test void commercialAndAdaptationPermissionsRemainExactNotImplicit(){
        assertEquals(ModuleInstallDeniedException.Reason.COMMERCIAL_USE_DENIED,assertThrows(ModuleInstallDeniedException.class,()->hold(request("one",91,42,claimId,true,Collections.emptySet()))).getReason());
        assertEquals(ModuleInstallDeniedException.Reason.ADAPTATION_DENIED,assertThrows(ModuleInstallDeniedException.class,()->hold(request("two",91,42,claimId,false,Collections.singleton(ModuleInstallRequest.Change.ADAPT_CONTENT)))).getReason());
        assertEquals(0,count("w18_module_install_reservation"));
    }
    @Test void wrongBuyerAndOtherOwnersCannotAcquireAuthority(){
        assertThrows(ModuleInstallDeniedException.class,()->reserve.reserve(43,request("one",91),until()));
        denied("GRANT_SCOPE_DENIED",()->reserve.reserve(43,request("one",91,43,claimId,false,Collections.emptySet()),until()));
        jdbc.update("UPDATE content_draft SET owner_member_id=43 WHERE id=91");denied("DRAFT_SCOPE_DENIED",()->hold(request("two",91)));
    }
    @Test void noScopeOrStaleScopeCannotUseRealDraft(){
        jdbc.update("DELETE FROM w18_content_draft_install_scope WHERE draft_id=91");denied("DRAFT_SCOPE_UNAVAILABLE",()->hold(request("one",91)));
        scopes.prepare(42,91,1,"region-1");jdbc.update("UPDATE content_draft SET payload_hash=? WHERE id=91",FreePublicationSnapshot.sha(new byte[]{1}));
        denied("DRAFT_SCOPE_UNAVAILABLE",()->hold(request("two",91)));
    }
    @Test void regionIsExplicitImmutablePerDraftVersionAndRightsChecked(){
        denied("DRAFT_SCOPE_CONFLICT",()->scopes.prepare(42,91,1,"elsewhere"));
        assertThrows(IllegalArgumentException.class,()->scopes.prepare(42,91,1,""));
        jdbc.update("DELETE FROM w18_content_draft_install_scope WHERE draft_id=91");scopes.prepare(42,91,1,"elsewhere");
        assertEquals(ModuleInstallDeniedException.Reason.REGION_NOT_GRANTED,assertThrows(ModuleInstallDeniedException.class,()->hold(request("one",91))).getReason());
    }
    @Test void actualEditorCasChangeInvalidatesReservedInstall(){
        ModuleInstallRequest request=request("one",91);hold(request);
        ContentDraft draft=new ContentDraft();draft.setId(91L);draft.setOwnerMemberId(42L);draft.setPayloadJson("{\"title\":\"edited\"}");draft.setPayloadHash(FreePublicationSnapshot.sha(new byte[]{9}));draft.setUpdatedByDevice("editor");
        assertEquals(1,drafts.updateDraftCas(draft,1L));denied("DRAFT_REVISION_MISMATCH",()->consume(request));assertEquals(0,count("w18_module_install_usage"));
    }
    @Test void actualEditorDeleteInvalidatesReservedInstall(){
        ModuleInstallRequest request=request("one",91);hold(request);assertEquals(1,drafts.deleteDraftCas(91L,42L,1L));
        denied("DRAFT_NOT_EDITABLE",()->consume(request));assertEquals(0,count("w18_module_owned_draft"));
    }
    @Test void actualEditorPublishInvalidatesReservedInstall(){
        ModuleInstallRequest request=request("one",91);hold(request);assertEquals(1,drafts.publishDraftCas(91L,42L,1L,501L));
        denied("DRAFT_NOT_EDITABLE",()->consume(request));assertEquals(0,count("w18_module_owned_draft"));
    }
    @Test void ordinarySellerRetirementPreservesReservedRightsAndStopsClaims(){
        ModuleInstallRequest request=request("one",91);hold(request);lifecycle.retire(8,"publication-1");consume(request);
        denied("PUBLICATION_NOT_CLAIMABLE",()->service.claim(43,"publication-1",digest,"new"));
    }
    @Test void sellerWithdrawalBetweenReserveAndConsumeDeniesNewUse(){
        ModuleInstallRequest request=request("one",91);hold(request);lifecycle.withdraw(8,"publication-1");
        denied("PUBLICATION_NOT_INSTALLABLE",()->consume(request));assertEquals(0,count("w18_module_install_usage"));
        reserve.release(42,request.getBuyer(),"one",ModuleInstallReservationService.Release.CANCELLED);
    }
    @Test void buyerRevocationBetweenReserveAndConsumeDeniesUseWithoutRenewal(){
        ModuleInstallRequest request=request("one",91);hold(request);lifecycle.revokeOwnedClaim(42,claimId);
        denied("GRANT_INACTIVE",()->consume(request));denied("ALREADY_CLAIMED",()->service.claim(42,"publication-1",digest,"new"));assertEquals(0,count("w18_module_install_usage"));
    }
    @Test void lifecycleWritesRequireExactActiveOwner(){
        denied("SELLER_SCOPE_DENIED",()->lifecycle.withdraw(43,"publication-1"));denied("GRANT_SCOPE_DENIED",()->lifecycle.revokeOwnedClaim(43,claimId));
        jdbc.update("UPDATE ums_member SET status=0 WHERE id=8");denied("BUYER_SCOPE_DENIED",()->lifecycle.retire(8,"publication-1"));
    }
    @Test void consumedReplayAfterWithdrawalRevocationExpiryAndEditorDeletionIsHistorical(){
        ModuleInstallRequest request=request("one",91);hold(request);ModuleInstallationReceipt first=consume(request);
        lifecycle.withdraw(8,"publication-1");lifecycle.revokeOwnedClaim(42,claimId);drafts.deleteDraftCas(91L,42L,1L);time.set(NOW.plusSeconds(7200));
        ModuleInstallationReceipt second=consume(request);assertEquals(first.getOwnedDraftId(),second.getOwnedDraftId());assertEquals(1,count("w18_module_install_usage"));
        jdbc.update("UPDATE ums_member SET status=0 WHERE id=42");assertThrows(ModuleInstallDeniedException.class,()->consume(request));
    }
    @Test void duplicateConcurrentConsumesCommitOneUsageAndDraft() throws Exception {
        ModuleInstallRequest request=request("one",91);hold(request);ExecutorService pool=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);
        try {List<Future<Long>> calls=new ArrayList<>();for(int n=0;n<4;n++)calls.add(pool.submit(()->{start.await();return consume(request).getOwnedDraftId();}));
            start.countDown();Set<Long> ids=new HashSet<>();for(Future<Long> call:calls)ids.add(call.get(15,TimeUnit.SECONDS));assertEquals(1,ids.size());assertEquals(1,count("w18_module_install_usage"));
        }finally{pool.shutdownNow();}
    }
    @Test void competingTargetsCannotExceedFrozenOneThemeQuota() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {List<Future<Boolean>> calls=new ArrayList<>();for(long target:Arrays.asList(91L,92L))calls.add(pool.submit(()->{start.await();try{hold(request("target-"+target,target));return true;}catch(ModuleInstallDeniedException denied){assertEquals(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED,denied.getReason());return false;}}));
            start.countDown();int won=0;for(Future<Boolean> call:calls)if(call.get(15,TimeUnit.SECONDS))won++;assertEquals(1,won);assertEquals(1,count("w18_module_install_reservation"));
        }finally{pool.shutdownNow();}
    }
    @Test void consumedQuotaStillCountsWhenLaterReservationTriesAnotherTheme(){
        ModuleInstallRequest first=request("one",91);hold(first);consume(first);
        assertEquals(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED,assertThrows(ModuleInstallDeniedException.class,()->hold(request("two",92))).getReason());
    }
    @Test void cancellationReleasesHoldWithoutMintingOrErasingGrant(){
        ModuleInstallRequest first=request("one",91);hold(first);reserve.release(42,first.getBuyer(),"one",ModuleInstallReservationService.Release.CANCELLED);
        ModuleInstallRequest second=request("two",92);hold(second);consume(second);assertEquals(1,count("w18_free_claim"));assertEquals(1,count("w18_module_install_usage"));
    }
    @Test void lateStorageFailureRollsBackUsageOwnedContentAndConsume(){
        ModuleInstallRequest request=request("one",91);hold(request);
        probe.after=id->{if(id.endsWith(".insertUsage"))throw new IllegalStateException("injected");};
        assertTrue(assertThrows(RuntimeException.class,()->consume(request)).getMessage().contains("injected"));
        assertEquals(0,count("w18_module_owned_draft"));assertEquals(0,count("w18_module_install_usage"));
        probe.after=id->{};consume(request);
    }
    @Test void deadlineCrossedDuringWriteRollsBackAllInstallationState(){
        ModuleInstallRequest request=request("one",91);hold(request);
        probe.after=id->{if(id.endsWith(".insertUsage"))time.set(until());};
        assertThrows(ModuleReservationException.class,()->consume(request));assertEquals(0,count("w18_module_install_usage"));assertEquals(0,count("w18_module_owned_draft"));
    }
    @Test void adapterRejectsAutocommitWrongDatasourceAndReadsBeforeLocks(){
        denied("INSTALL_TRANSACTION_REQUIRED",()->authority.findVerifiedGrant(claimId));
        denied("INSTALL_DATASOURCE_MISMATCH",()->new FreeClaimInstallationAuthority(session,new DataSourceTransactionManager(new DriverManagerDataSource()),clock));
        TransactionTemplate tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.execute(status->{denied("INSTALL_LOCKS_REQUIRED",()->authority.findVerifiedGrant(claimId));return null;});
    }
    @Test void sourceOrTermsMutationAfterReservationFailsClosed(){
        ModuleInstallRequest request=request("one",91);hold(request);jdbc.update("UPDATE w18_free_publication SET terms=?",new byte[]{1});
        assertThrows(IllegalArgumentException.class,()->consume(request));assertEquals(0,count("w18_module_owned_draft"));
        jdbc.update("UPDATE w18_free_publication SET terms=?,content=?",snapshot.getTerms(),new byte[]{2});assertThrows(IllegalArgumentException.class,()->consume(request));
    }
    @Test void memberRevocationBetweenReserveAndConsumeDeniesContent(){
        ModuleInstallRequest request=request("one",91);hold(request);jdbc.update("UPDATE ums_member SET del_flag=1 WHERE id=42");assertThrows(ModuleInstallDeniedException.class,()->consume(request));assertEquals(0,count("w18_module_owned_draft"));
    }
    @Test void withdrawalWaitsForInstallCommitAndHistoricalReplaySurvives() throws Exception {raceWriter(()->lifecycle.withdraw(8,"publication-1"));}
    @Test void claimRevocationWaitsForInstallCommitAndHistoricalReplaySurvives() throws Exception {raceWriter(()->lifecycle.revokeOwnedClaim(42,claimId));}
    @Test void editorCasWaitsForInstallCommitAndHistoricalReplaySurvives() throws Exception {raceWriter(()->assertEquals(1,drafts.deleteDraftCas(91L,42L,1L)));}
    @Test void activityDraftIsARealSupportedDestinationAndSourceRemainsMemberText(){
        jdbc.update("UPDATE content_draft SET business_type='ACTIVITY' WHERE id=91");
        ModuleInstallRequest request=request("activity",91);hold(request);consume(request);
        assertEquals(ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE,snapshot.getVersion().getSourceKind());
        assertEquals(91L,jdbc.queryForObject("SELECT target_draft_id FROM w18_module_owned_draft",Long.class));
    }
    @Test void resourceBindingRequestsFailClosedWithoutInventedPermission(){
        ModuleInstallRequest original=request("bound",91);
        ModuleInstallRequest bound=new ModuleInstallRequest(original.getBuyer(),original.getRequestId(),claimId,"module-1","version-1",original.getExpectedContentHash(),"offer-v1",original.getExpectedTermsHash(),91,1,false,Collections.emptySet(),Collections.singletonMap("poi",new ModuleInstallRequest.ResourceRef(ModuleVersionSnapshot.BindingKind.LOCATION,1)));
        denied("BINDING_AUTHORITY_UNAVAILABLE",()->hold(bound));assertEquals(0,count("w18_module_install_reservation"));
    }
    @Test void transactionContextIsNotReusableAfterRollbackOrCommit(){
        ModuleInstallRequest request=request("context",91);TransactionTemplate tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.execute(status->{authority.lockForReservation(42,request);assertNotNull(authority.findVerifiedGrant(claimId));status.setRollbackOnly();return null;});
        tx.execute(status->{denied("INSTALL_LOCKS_REQUIRED",()->authority.findVerifiedGrant(claimId));return null;});
        hold(request);consume(request);denied("INSTALL_TRANSACTION_REQUIRED",()->authority.loadReviewedContent(snapshot.getVersion()));
    }
    @Test void unsupportedBuyerKindNeverCoercesIntoMemberIdentity(){
        ModuleInstallRequest original=request("org",91);
        ModuleInstallRequest org=new ModuleInstallRequest(new BuyerPrincipal(BuyerPrincipal.Kind.ORGANIZATION,42),"org",claimId,"module-1","version-1",original.getExpectedContentHash(),"offer-v1",original.getExpectedTermsHash(),91,1,false,Collections.emptySet(),Collections.emptyMap());
        assertThrows(ModuleInstallDeniedException.class,()->hold(org));assertEquals(0,count("w18_module_install_reservation"));
    }
    // Test-only access to our own retained composition; production exposes no mapper escape hatch.
    private ModuleReservationStore retainedReservations() {
        return (ModuleReservationStore)org.springframework.test.util.ReflectionTestUtils.getField(installer,"reservations");
    }
    private SqlSessionTemplate aliasSession() throws Exception {
        DriverManagerDataSource primary=(DriverManagerDataSource)manager.getDataSource();
        DriverManagerDataSource other=new DriverManagerDataSource(primary.getUrl(),primary.getUsername(),primary.getPassword());
        Configuration configuration=new Configuration();
        configuration.setEnvironment(new Environment("same-database-different-datasource",new SpringManagedTransactionFactory(),other));
        for(String mapper:Arrays.asList("ModuleReservationStore","ModuleInstallationStore")) {
            String path="mapper/marketplace/"+mapper+".xml";
            try(InputStream input=Resources.getResourceAsStream(path)){new XMLMapperBuilder(input,configuration,path,configuration.getSqlFragments()).parse();}
        }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    }
    @Test void independentWrongReservationDatasourceRejectedBeforeAnyWrite() throws Exception {
        ModuleReservationStore wrong=aliasSession().getMapper(ModuleReservationStore.class);
        // Original red probe injected expiry after insertScope and observed one autocommitted hold.
        // Construction now rejects the same datasource alias before even a request mutex can write.
        denied("INSTALL_MAPPER_MISMATCH",()->new ModuleInstallReservationService(wrong,authority,manager,clock));
        assertEquals(0,count("w18_module_install_reservation"));assertEquals(0,count("w18_module_install_mutex"));
    }
    @Test void independentWrongInstallationDatasourceRejectedBeforeAnyWrite() throws Exception {
        ModuleInstallationStore wrong=aliasSession().getMapper(ModuleInstallationStore.class);
        denied("INSTALL_MAPPER_MISMATCH",()->new ModuleTextDraftInstallationService(retainedReservations(),wrong,authority,manager,clock));
        assertEquals(0,count("w18_module_owned_draft"));assertEquals(0,count("w18_module_install_usage"));assertEquals(0,count("w18_module_install_mutex"));
    }
    @Test void arbitraryStoreWrappersCannotClaimVerifiedSessionProvenance(){
        ModuleReservationStore wrapped=mock(ModuleReservationStore.class,delegatesTo(reservations));
        denied("INSTALL_MAPPER_MISMATCH",()->new ModuleInstallReservationService(wrapped,authority,manager,clock));
        // Even a separately created proxy from the same template is not a retained factory store.
        denied("INSTALL_MAPPER_MISMATCH",()->new ModuleInstallReservationService(reservations,authority,manager,clock));
        assertEquals(0,count("w18_module_install_mutex"));
    }
    @Test void alternateTransactionManagerIsRejectedEvenInsideBoundOuterTransaction(){
        TransactionTemplate outer=new TransactionTemplate(manager);outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.execute(status->{
            DataSourceTransactionManager alternate=new DataSourceTransactionManager(manager.getDataSource());
            denied("INSTALL_DATASOURCE_MISMATCH",()->new ModuleInstallReservationService(reservations,authority,alternate,clock));
            denied("INSTALL_DATASOURCE_MISMATCH",()->new ModuleTextDraftInstallationService(reservations,installs,authority,alternate,clock));
            return null;
        });assertEquals(0,count("w18_module_install_mutex"));
    }
    @Test void sharedFactoryReservationLateExpiryRollsBackEveryWriteAndCanRetry(){
        ModuleInstallRequest request=request("late-hold",91);
        probe.after=id->{if(id.endsWith(".insertScope"))time.set(until());};
        assertThrows(ModuleReservationException.class,()->hold(request));
        assertEquals(0,count("w18_module_install_mutex"));assertEquals(0,count("w18_module_install_reservation"));assertEquals(0,count("w18_module_install_scope"));
        probe.after=id->{};time.set(NOW);hold(request);consume(request);assertEquals(1,count("w18_module_install_usage"));
    }
    @Test void nonSpringSessionsAndWrongIsolationNeverProvideAuthority(){
        Configuration configuration=new Configuration();
        configuration.setEnvironment(new Environment("non-spring",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),manager.getDataSource()));
        SqlSessionTemplate raw=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        denied("INSTALL_DATASOURCE_MISMATCH",()->new FreeClaimInstallationAuthority(raw,manager,clock));
        TransactionTemplate tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.execute(status->{denied("INSTALL_TRANSACTION_REQUIRED",()->authority.lockForReservation(42,request("wrong-isolation",91)));return null;});
        assertEquals(0,count("w18_module_install_reservation"));
    }
    @Test void transactionContextSuspendsForNestedTransactionAndRestoresOnlyToOriginal(){
        TransactionTemplate outer=new TransactionTemplate(manager);outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.execute(status->{
            authority.lockForReservation(42,request("outer",91));ModuleLicenseGrant grant=authority.findVerifiedGrant(claimId);
            TransactionTemplate nested=new TransactionTemplate(manager);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);nested.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            nested.execute(inner->{denied("INSTALL_LOCKS_REQUIRED",()->authority.findVerifiedGrant(claimId));return null;});
            assertSame(grant,authority.findVerifiedGrant(claimId));return null;
        });
        outer.execute(status->{denied("INSTALL_LOCKS_REQUIRED",()->authority.findVerifiedGrant(claimId));return null;});
    }
    @Test void futureIssuedClaimAndExactExpiryDoNotGrantUse(){
        jdbc.update("UPDATE w18_free_claim SET created_at=?",Timestamp.from(NOW.plusSeconds(1)));
        denied("GRANT_INACTIVE",()->hold(request("future",91)));
        jdbc.update("UPDATE w18_free_claim SET created_at=?",Timestamp.from(NOW));time.set(NOW.plusSeconds(3600));
        denied("GRANT_INACTIVE",()->reserve.reserve(42,request("expired",91),NOW.plusSeconds(4200)));
        assertEquals(0,count("w18_module_install_reservation"));
    }
    @Test void persistedClaimCannotExtendFrozenDeadlineOrSubstituteDocument(){
        jdbc.update("UPDATE w18_free_claim SET valid_until=?",Timestamp.from(NOW.plusSeconds(7200)));
        denied("GRANT_CORRUPT",()->hold(request("extended",91)));
        jdbc.update("UPDATE w18_free_claim SET valid_until=?",Timestamp.from(NOW.plusSeconds(3600)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE w18_free_claim SET document_hash=?",FreePublicationSnapshot.sha(new byte[]{7})));
        assertEquals(0,count("w18_module_install_reservation"));
    }
    @Test void sidecarOwnerCannotStandInForRealOwnerAndUnknownTargetKindFailsClosed(){
        jdbc.update("UPDATE w18_content_draft_install_scope SET owner_member_id=43 WHERE draft_id=91");
        denied("DRAFT_SCOPE_UNAVAILABLE",()->hold(request("scope-owner",91)));
        jdbc.update("UPDATE w18_content_draft_install_scope SET owner_member_id=42 WHERE draft_id=91");
        // MySQL CHECK error 3819 is not categorized by Spring 5.3's SQL error map; inspect the exact constraint instead.
        org.springframework.dao.DataAccessException invalidKind=assertThrows(org.springframework.dao.DataAccessException.class,
                ()->jdbc.update("UPDATE content_draft SET business_type='MEMBER_TEMPLATE' WHERE id=91"));
        assertTrue(invalidKind.getMessage().contains("chk_content_draft_business_type"));
        assertEquals("TOPIC",jdbc.queryForObject("SELECT business_type FROM content_draft WHERE id=91",String.class));
        FreeInstallTargetRow invalid=new FreeInstallTargetRow();invalid.setOwnerMemberId(42L);invalid.setStatus("DRAFT");invalid.setBusinessType("MEMBER_TEMPLATE");
        denied("DRAFT_NOT_EDITABLE",()->FreeContentDraftScopeService.requireEditable(invalid,42));assertEquals(0,count("w18_module_install_reservation"));
    }
    @Test void historicalReplayStillRejectsChangedTargetAndDeadlineWithoutSourceRead(){
        ModuleInstallRequest request=request("historical",91);hold(request);consume(request);lifecycle.revokeOwnedClaim(42,claimId);
        jdbc.update("UPDATE w18_free_publication SET content=?",new byte[]{9});
        assertEquals(ModuleReservationException.Reason.IDEMPOTENCY_CONFLICT,assertThrows(ModuleReservationException.class,()->installer.consume(42,request,until().plusSeconds(1))).getReason());
        assertEquals(ModuleReservationException.Reason.IDEMPOTENCY_CONFLICT,assertThrows(ModuleReservationException.class,()->consume(request("historical",92))).getReason());
        consume(request);assertEquals(1,count("w18_module_owned_draft"));assertEquals(1,count("w18_module_install_usage"));
    }
    @Test void reconfiguredSessionDatasourceFailsBeforeFirstWrite() throws Exception {
        org.apache.ibatis.mapping.Environment original=session.getSqlSessionFactory().getConfiguration().getEnvironment();
        try {
            session.getSqlSessionFactory().getConfiguration().setEnvironment(aliasSession().getSqlSessionFactory().getConfiguration().getEnvironment());
            denied("INSTALL_DATASOURCE_MISMATCH",()->hold(request("reconfigured",91)));
        } finally {session.getSqlSessionFactory().getConfiguration().setEnvironment(original);}
        assertEquals(0,count("w18_module_install_mutex"));assertEquals(0,count("w18_module_install_reservation"));
    }
    @Test void lockedPublicationBytesRemainDefensiveCopiesAndCannotBeLoadedFromForgedSnapshot(){
        TransactionTemplate tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.execute(status->{
            authority.lockForReservation(42,request("bytes",91));
            ModuleVersionSnapshot version=authority.findInstallableVersion("version-1");LicenseTermsSnapshot terms=authority.findVerifiedGrant(claimId).getOffer().getTerms();
            byte[] source=authority.loadReviewedContent(version),document=authority.loadFrozenTerms(terms);source[0]=0;document[0]=0;
            assertArrayEquals(snapshot.getContent(),authority.loadReviewedContent(version));assertArrayEquals(snapshot.getTerms(),authority.loadFrozenTerms(terms));
            denied("PUBLICATION_CHANGED",()->authority.loadReviewedContent(snapshot.getVersion()));
            denied("PUBLICATION_CHANGED",()->authority.loadFrozenTerms(snapshot.getOffer().getTerms()));return null;
        });
    }
    @Test void resourceBearingReviewedPublicationCannotInstallWithEmptyClientBindings(){
        byte[] content="{\"schema\":\"w18-member-text-v1\",\"title\":\"Bound fixture\",\"bindings\":{\"poi\":\"LOCATION\"}}".getBytes(StandardCharsets.UTF_8);
        ModuleVersionSnapshot v=snapshot.getVersion();LicenseOfferSnapshot o=snapshot.getOffer();
        ModuleVersionSnapshot bound=new ModuleVersionSnapshot(v.getCreator(),v.getModuleId(),v.getVersionId(),v.getVersion(),1,v.getSourceKind(),v.getSourceId(),v.getSourceRevision(),content,FreePublicationSnapshot.sha(content),v.getApprovalReference(),Collections.singletonMap("poi",ModuleVersionSnapshot.BindingKind.LOCATION));
        LicenseOfferSnapshot offer=new LicenseOfferSnapshot("offer-bound",o.getOfferVersion(),o.getModuleId(),o.getSeller(),o.getBuyerKinds(),o.getAcquisition(),o.getPriceMinor(),o.getCurrency(),o.getTerms());
        snapshot=new FreePublicationSnapshot(bound,offer,content,snapshot.getTerms(),snapshot.getValidUntil());digest=FreePublicationSnapshot.sha(snapshot.encode().getBytes(StandardCharsets.UTF_8));seed("publication-bound");
        String boundClaim=service.claim(42,"publication-bound",digest,"bound-license").getClaimId();
        denied("BINDING_AUTHORITY_UNAVAILABLE",()->hold(request("bound-source",91,42,boundClaim,false,Collections.emptySet())));
        assertEquals(0,count("w18_module_install_reservation"));
    }
    private void raceWriter(Runnable writer) throws Exception {
        ModuleInstallRequest request=request("one",91);hold(request);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch locked=new CountDownLatch(1),proceed=new CountDownLatch(1),started=new CountDownLatch(1);
        probe.before=id->{if(id.endsWith(".insertOwnedDraft")){locked.countDown();assertTrue(proceed.await(10,TimeUnit.SECONDS));}};
        try {Future<ModuleInstallationReceipt> installing=pool.submit(()->consume(request));assertTrue(locked.await(10,TimeUnit.SECONDS));
            Future<?> mutation=pool.submit(()->{started.countDown();writer.run();});assertTrue(started.await(10,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->mutation.get(150,TimeUnit.MILLISECONDS));proceed.countDown();ModuleInstallationReceipt first=installing.get(15,TimeUnit.SECONDS);mutation.get(15,TimeUnit.SECONDS);
            assertEquals(first.getOwnedDraftId(),consume(request).getOwnedDraftId());assertEquals(1,count("w18_module_install_usage"));
        }finally{proceed.countDown();pool.shutdownNow();}
    }
}
