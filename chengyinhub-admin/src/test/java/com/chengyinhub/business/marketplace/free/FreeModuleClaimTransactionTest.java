package com.chengyinhub.business.marketplace.free;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.free.store.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.junit.jupiter.api.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Real migration/MyBatis transaction tests. Publication ingestion/review remains a labeled fixture. */
class FreeModuleClaimTransactionTest {
    private static final Instant NOW=Instant.parse("2026-10-03T00:00:00Z");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private FreeModuleClaimStore store;
    private FreeModuleClaimService service;
    private FreePublicationSnapshot snapshot;
    private String digest;
    @BeforeEach void setup() throws Exception {
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
        digest=FreePublicationSnapshot.sha(snapshot.encode().getBytes(StandardCharsets.UTF_8));seed("publication-1");
    }
    protected DriverManagerDataSource createDataSource() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:free_"+System.nanoTime()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        ds.setDriverClassName("org.h2.Driver");return ds;
    }
    protected void applySchema(JdbcTemplate db) throws Exception {
        Path p=Paths.get("sql/migration_w18_free_claim_registry_20261003.sql");
        if(!Files.exists(p))p=Paths.get("chengyinhub-admin").resolve(p);
        String sql=new String(Files.readAllBytes(p),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","")
                .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","");
        // H2 covers runtime DDL only. MySQL-specific metadata assertions execute unchanged in the MySQL subclass.
        for(String s:sql.split(";"))if(!s.trim().isEmpty() && !s.trim().startsWith("SELECT"))db.execute(s);
    }
    private void seed(String id){
        jdbc.update("insert into w18_free_publication values(?,?,?,?,?,?,?,?,?)",id,snapshot.ownershipKey(),8,11,digest,snapshot.encode(),snapshot.getContent(),snapshot.getTerms(),Timestamp.from(NOW.plusSeconds(3600)));
        jdbc.update("insert into w18_free_publication_policy values(?,'LISTED')",id);
    }
    private FreeModuleClaimService.Receipt claim(String key){return service.claim(42,"publication-1",digest,key);}
    private int count(){return jdbc.queryForObject("select count(*) from w18_free_claim",Integer.class);}
    private static void denied(String reason,Runnable action){assertEquals(reason,assertThrows(IllegalStateException.class,action::run).getMessage());}

    @Test void durableClaimLoadsExactExplicitRightsAndSourceEvidence(){
        FreeModuleClaimService.Receipt receipt=claim("one");ModuleLicenseGrant grant=service.findCurrentGrant(42,receipt.getClaimId());
        assertEquals(ModuleLicenseGrant.EvidenceKind.VERIFIED_FREE_CLAIM,grant.getEvidenceKind());
        assertEquals("w18-free-claim:"+receipt.getClaimId(),grant.getAuthorityReference());
        assertEquals(snapshot.getOffer().getTerms().getTermsHash(),grant.getOffer().getTerms().getTermsHash());
        assertEquals(snapshot.getVersion().getContentHash(),grant.getGrantedVersionHashes().get("version-1"));
        assertEquals(NOW.plusSeconds(3600),grant.getValidUntil());
        assertFalse(receipt.isPublished());assertFalse(receipt.isExecutable());assertEquals(1,count());
    }
    @Test void responseLossReplayIsDurableAndNeverRenewsRevokedGrant(){
        FreeModuleClaimService.Receipt first=claim("one");jdbc.update("update w18_free_claim set state='REVOKED'");
        jdbc.update("update w18_free_publication_policy set state='EMERGENCY_BLOCKED'");
        service=new FreeModuleClaimService(store,manager,Clock.fixed(NOW.plusSeconds(7200),ZoneOffset.UTC));
        FreeModuleClaimService.Receipt replay=claim("one");assertEquals(first.getClaimId(),replay.getClaimId());
        assertEquals(first.getCreatedAt(),replay.getCreatedAt());assertEquals("REVOKED",replay.getState());assertEquals(1,count());
        denied("PUBLICATION_NOT_INSTALLABLE",()->service.findCurrentGrant(42,first.getClaimId()));
    }
    @Test void differentRequestKeyCannotMultiplyGrantOrReinstateRevokedGrant(){
        claim("one");jdbc.update("update w18_free_claim set state='REVOKED'");
        denied("ALREADY_CLAIMED",()->claim("two"));assertEquals(1,count());
    }
    @Test void requestReplayRejectsChangedPublicationAndDigest(){
        claim("one");assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->seed("publication-2"));
        denied("IDEMPOTENCY_CONFLICT",()->service.claim(42,"publication-2",digest,"one"));
        denied("IDEMPOTENCY_CONFLICT",()->service.claim(42,"publication-1",FreePublicationSnapshot.sha(new byte[]{1}),"one"));
    }
    @Test void currentMemberTruthProtectsIssuanceReadAndHistoricalReplay(){
        String id=claim("one").getClaimId();jdbc.update("update ums_member set status=0 where id=42");
        denied("BUYER_SCOPE_DENIED",()->claim("one"));denied("BUYER_SCOPE_DENIED",()->service.findCurrentGrant(42,id));
        jdbc.update("update ums_member set status=1,del_flag=1 where id=42");denied("BUYER_SCOPE_DENIED",()->claim("two"));
        denied("BUYER_SCOPE_DENIED",()->service.claim(99,"publication-1",digest,"one"));
    }
    @Test void otherMembersCannotReadOrReplayOwnedGrant(){
        String id=claim("one").getClaimId();denied("GRANT_SCOPE_DENIED",()->service.findCurrentGrant(43,id));
        String other=service.claim(43,"publication-1",digest,"one").getClaimId();assertNotEquals(id,other);assertEquals(2,count());
    }
    @Test void ordinaryRetirementPreservesFrozenOwnedRightsButStopsNewClaims(){
        String id=claim("one").getClaimId();jdbc.update("update w18_free_publication_policy set state='RETIRED'");
        assertNotNull(service.findCurrentGrant(42,id));denied("PUBLICATION_NOT_CLAIMABLE",()->service.claim(43,"publication-1",digest,"one"));
    }
    @Test void emergencyWithdrawalAndGrantSuspensionDenyCurrentUse(){
        String id=claim("one").getClaimId();jdbc.update("update w18_free_claim set state='SUSPENDED'");
        denied("GRANT_INACTIVE",()->service.findCurrentGrant(42,id));jdbc.update("update w18_free_claim set state='ACTIVE'");
        jdbc.update("update w18_free_publication_policy set state='EMERGENCY_BLOCKED'");
        denied("PUBLICATION_NOT_INSTALLABLE",()->service.findCurrentGrant(42,id));
        denied("PUBLICATION_NOT_CLAIMABLE",()->service.claim(43,"publication-1",digest,"one"));
    }
    @Test void explicitValidityExpiresNewClaimsAndCurrentUseWithoutDeletingHistory(){
        String id=claim("one").getClaimId();service=new FreeModuleClaimService(store,manager,Clock.fixed(NOW.plusSeconds(3600),ZoneOffset.UTC));
        denied("GRANT_INACTIVE",()->service.findCurrentGrant(42,id));denied("PUBLICATION_EXPIRED",()->service.claim(43,"publication-1",digest,"one"));
        assertEquals(id,claim("one").getClaimId());assertEquals(1,count());
    }
    @Test void missingRegistryNeverFallsBackToTicketSkuOrClientPaidFacts(){
        denied("PUBLICATION_NOT_FOUND",()->service.claim(42,"paid-order-line-1",digest,"one"));assertEquals(0,count());
    }
    @Test void alteredSourceOrTermsAndMissingExplicitPolicyRejectBeforeGrant(){
        jdbc.update("update w18_free_publication set content=?",new byte[]{1});assertThrows(IllegalArgumentException.class,()->claim("one"));assertEquals(0,count());
        jdbc.update("update w18_free_publication set content=?,terms=?",snapshot.getContent(),new byte[]{2});
        assertThrows(IllegalArgumentException.class,()->claim("one"));assertEquals(0,count());
        String missing=snapshot.encode().replace("\"commercialUse\":\"PROHIBITED\",","");
        assertThrows(IllegalArgumentException.class,()->FreePublicationSnapshot.decode(missing,snapshot.getContent(),snapshot.getTerms()));
    }
    @Test void publicationDigestAndIssuerMetadataCannotBeSubstituted(){
        jdbc.update("update w18_free_publication set seller_member_id=43");denied("PUBLICATION_CORRUPT",()->claim("one"));
        jdbc.update("update w18_free_publication set seller_member_id=8,snapshot_json='{}'");
        denied("PUBLICATION_CORRUPT",()->claim("one"));assertEquals(0,count());
    }
    @Test void failedInsertRollsBackAndCanRetrySameRequest(){
        FreeModuleClaimStore broken=mock(FreeModuleClaimStore.class,delegatesTo(store));
        doAnswer(c->{store.insertClaim(c.getArgument(0));throw new IllegalStateException("injected");}).when(broken).insertClaim(any());
        FreeModuleClaimService failing=new FreeModuleClaimService(broken,manager,Clock.fixed(NOW,ZoneOffset.UTC));
        denied("injected",()->failing.claim(42,"publication-1",digest,"one"));assertEquals(0,count());claim("one");assertEquals(1,count());
    }
    @Test void concurrentSameKeyCommitsOneGrantAndOneSourceEvent() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(6);CountDownLatch start=new CountDownLatch(1);
        try {List<Future<String>> calls=new ArrayList<Future<String>>();for(int i=0;i<6;i++)calls.add(pool.submit(()->{start.await();return claim("one").getClaimId();}));
            start.countDown();Set<String> ids=new HashSet<String>();for(Future<String> f:calls)ids.add(f.get(15,TimeUnit.SECONDS));
            assertEquals(1,ids.size());assertEquals(1,count());
        } finally {pool.shutdownNow();}
    }
    @Test void canonicalSnapshotRequiresCompletePoliciesAndRejectsUnknownFields(){
        assertEquals(snapshot.encode(),FreePublicationSnapshot.decode(snapshot.encode(),snapshot.getContent(),snapshot.getTerms()).encode());
        assertThrows(IllegalArgumentException.class,()->FreePublicationSnapshot.decode(snapshot.encode().replaceFirst("\\{","{\"paid\":true,"),snapshot.getContent(),snapshot.getTerms()));
        assertThrows(IllegalArgumentException.class,()->FreePublicationSnapshot.decode(snapshot.encode()+"{}",snapshot.getContent(),snapshot.getTerms()));
    }

    @Test void validityIsFrozenInBothPublicationAndClaimRecords(){
        String id=claim("one").getClaimId();jdbc.update("update w18_free_claim set valid_until=?",Timestamp.from(NOW.plusSeconds(9999)));
        denied("GRANT_CORRUPT",()->service.findCurrentGrant(42,id));
        jdbc.update("update w18_free_publication set grant_valid_until=?",Timestamp.from(NOW.plusSeconds(9999)));
        denied("PUBLICATION_CORRUPT",()->service.claim(43,"publication-1",digest,"one"));
    }
    @Test void paidOffersAndBuyerScopeExpansionCannotEnterFreeRegistry(){
        LicenseOfferSnapshot old=snapshot.getOffer();
        LicenseOfferSnapshot paid=new LicenseOfferSnapshot(old.getOfferId(),old.getOfferVersion(),old.getModuleId(),old.getSeller(),old.getBuyerKinds(),
                LicenseOfferSnapshot.Acquisition.PAID,1,"CNY",old.getTerms());
        assertThrows(IllegalArgumentException.class,()->new FreePublicationSnapshot(snapshot.getVersion(),paid,snapshot.getContent(),snapshot.getTerms(),snapshot.getValidUntil()));
        LicenseOfferSnapshot org=new LicenseOfferSnapshot(old.getOfferId(),old.getOfferVersion(),old.getModuleId(),old.getSeller(),EnumSet.allOf(BuyerPrincipal.Kind.class),
                LicenseOfferSnapshot.Acquisition.FREE,0,"CNY",old.getTerms());
        assertThrows(IllegalArgumentException.class,()->new FreePublicationSnapshot(snapshot.getVersion(),org,snapshot.getContent(),snapshot.getTerms(),snapshot.getValidUntil()));
    }
    @Test void competingKeysCannotAmplifyOneFrozenOffer() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {List<Future<String>> calls=new ArrayList<Future<String>>();for(String key:Arrays.asList("one","two"))calls.add(pool.submit(()->{
            start.await();try{return claim(key).getState();}catch(IllegalStateException expected){return expected.getMessage();}}));
            start.countDown();Set<String> results=new HashSet<String>();for(Future<String> f:calls)results.add(f.get(15,TimeUnit.SECONDS));
            assertEquals(new HashSet<String>(Arrays.asList("ACTIVE","ALREADY_CLAIMED")),results);assertEquals(1,count());
        } finally {pool.shutdownNow();}
    }
    @Test void schemaValidationRejectsUnsupportedContentBeforeIssuance(){
        byte[] unknown="{\"schema\":\"w18-member-text-v1\",\"title\":\"bad\",\"bindings\":{},\"script\":\"execute\"}".getBytes(StandardCharsets.UTF_8);
        ModuleVersionSnapshot v=snapshot.getVersion();ModuleVersionSnapshot changed=new ModuleVersionSnapshot(v.getCreator(),v.getModuleId(),v.getVersionId(),
                v.getVersion(),1,v.getSourceKind(),v.getSourceId(),v.getSourceRevision(),unknown,FreePublicationSnapshot.sha(unknown),v.getApprovalReference(),Collections.emptyMap());
        assertThrows(IllegalArgumentException.class,()->new FreePublicationSnapshot(changed,snapshot.getOffer(),unknown,snapshot.getTerms(),snapshot.getValidUntil()));
    }
    @Test void futureIssuanceCannotActAsCurrentEntitlement(){
        String id=claim("one").getClaimId();jdbc.update("update w18_free_claim set created_at=?",Timestamp.from(NOW.plusSeconds(60)));
        denied("GRANT_INACTIVE",()->service.findCurrentGrant(42,id));
    }
    @Test void expiryDuringInsertRollsBackIssuanceAndRequestKey(){
        java.util.concurrent.atomic.AtomicReference<Instant> now=new java.util.concurrent.atomic.AtomicReference<Instant>(NOW);
        Clock moving=new Clock(){public Instant instant(){return now.get();}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}};
        FreeModuleClaimStore delayed=mock(FreeModuleClaimStore.class,delegatesTo(store));
        doAnswer(c->{int result=store.insertClaim(c.getArgument(0));now.set(NOW.plusSeconds(3600));return result;}).when(delayed).insertClaim(any());
        FreeModuleClaimService expiring=new FreeModuleClaimService(delayed,manager,moving);
        denied("PUBLICATION_EXPIRED",()->expiring.claim(42,"publication-1",digest,"one"));assertEquals(0,count());
        claim("one");assertEquals(1,count());
    }
}
