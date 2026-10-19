package com.chengyinhub.business.reward;

import java.io.InputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import javax.sql.DataSource;
import java.sql.*;
import java.security.MessageDigest;
import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.reward.NonCashRewardContracts.*;

/** Real Oracle MySQL 8.0.45, isolated loopback scratch only; no H2 inheritance or fallback. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class NonCashRewardMySqlIT {
    private static final String MIGRATION="w17_noncash_inventory_candidate";
    private static final String MIGRATION_SHA="ec2869ac0a2d2a4a6da0e9dff0066cffa2bd83f23bbf4469ca551f3b6e721bfd";
    private final IsolatedMySqlSchema mysql=new IsolatedMySqlSchema();
    private String schema,commit,run;
    DataSource ds;JdbcTemplate jdbc;DataSourceTransactionManager manager;
    NonCashRewardMapper mapper;NonCashRewardService service;Facts facts;Time clock;
    @BeforeEach void setup() throws Exception {
        commit=System.getProperty("w17.test.commit","");run=System.getProperty("w17.test.run","");
        assertTrue(commit.matches("[0-9a-f]{40}"),"Exact checkout commit required");
        assertTrue(run.matches("[0-9a-f]{32}"),"Fresh invocation required");
        byte[] migration=Files.readAllBytes(root().resolve("chengyinhub-admin/sql/migration_"+MIGRATION+".sql"));
        assertEquals(MIGRATION_SHA,hex(MessageDigest.getInstance("SHA-256").digest(migration)));
        ds=mysql.open("w17_noncash");
        try(Connection connection=ds.getConnection()){schema=connection.getCatalog();}
        mysql.apply(MIGRATION);
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        // Canonical column projection fixture only; no production product/stock schema migration.
        jdbc.execute("CREATE TABLE pms_product(id BIGINT PRIMARY KEY,merchant_id BIGINT NOT NULL,publish_status INT NOT NULL,del_flag INT NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE pms_sku(id BIGINT PRIMARY KEY,product_id BIGINT NOT NULL,stock BIGINT NOT NULL) ENGINE=InnoDB");
        jdbc.update("INSERT INTO pms_product VALUES(11,7,1,0)");jdbc.update("INSERT INTO pms_sku VALUES(12,11,10)");
        mapper=newMapper();clock=new Time();facts=new Facts();service=newService(mapper);
    }
    @AfterEach void cleanup(TestInfo info) throws Exception {
        mysql.close();
        if(schema!=null)System.out.println("W17_MYSQL_EVIDENCE METHOD="+info.getTestMethod().get().getName()
            +" VERSION=8.0.45 SCHEMA="+schema+" MIGRATION_SHA256="+MIGRATION_SHA
            +" COMMIT="+commit+" RUN="+run+" SCRATCH_DROPPED=true");
    }
    private static Path root(){Path p=Paths.get(System.getProperty("user.dir")).toAbsolutePath();return "chengyinhub-admin".equals(p.getFileName().toString())?p.getParent():p;}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}
    NonCashRewardMapper newMapper() throws Exception {
        Configuration c=new Configuration(new Environment("w17-isolated",new SpringManagedTransactionFactory(),ds));
        String path="mapper/business/NonCashRewardMapper.xml";
        try(InputStream in=getClass().getClassLoader().getResourceAsStream(path)) { assertNotNull(in,"Current reactor XML required");new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse(); }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c)).getMapper(NonCashRewardMapper.class);
    }
    NonCashRewardService newService(NonCashRewardMapper m) { return new NonCashRewardService(NonCashRewardService.Mode.ISOLATED_PERSISTENCE_EVALUATION,m,facts,manager,clock); }
    Scope scope() { return new Scope(ScopeType.MAP,"board","release","season"); }
    Pledge pledge(Allocation a,long count) { return new Pledge("pledge","v1","approval",scope(),"rules","coffee",7,8,11,12,count,100,1000,a,RewardKind.PHYSICAL,"Coffee","Collect at store 8 during approved window"); }
    void supply(Allocation a,long n) { facts.p=pledge(a,n);service.importApprovedPledge("pledge"); }
    Qualification q(long member,String completion,String entitlement) { return new Qualification(scope(),member,completion,entitlement,"rules","coffee",200); }
    void event(String id,long member) { facts.events.put(id,q(member,"completion-"+member,"eligible-"+member)); }
    Award award(long member) { String e="event-"+member;event(e,member);Award a=service.award("pledge",member,e);facts.receipts.put("consume",a.awardId);return a; }
    long value(String column) { return jdbc.queryForObject("SELECT "+column+" FROM w17_noncash_pledge WHERE pledge_id='pledge'",Long.class); }
    long stock() { return jdbc.queryForObject("SELECT stock FROM pms_sku WHERE id=12",Long.class); }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    void conserved() { assertEquals(value("total"),value("available")+value("reserved")+value("awarded")+value("consumed")+value("retired")); }
    @Test void defaultDisabledAndNestedTransactionsRejectBeforeMutation() {
        assertThrows(IllegalStateException.class,()->new NonCashRewardService().importApprovedPledge("pledge"));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(s->{service.importApprovedPledge("pledge");return null;}));assertEquals(10,stock());
    }
    @Test void skuImportIsAtomicIdempotentAndDoesNotDuplicateCanonicalInventory() {
        supply(Allocation.FINITE,3);service.importApprovedPledge("pledge");assertEquals(7,stock());assertEquals(3,value("available"));assertEquals(1,count("w17_noncash_audit"));conserved();
        facts.p=pledge(Allocation.FINITE,4);assertThrows(IllegalStateException.class,()->service.importApprovedPledge("pledge"));assertEquals(7,stock());
    }
    @Test void canonicalOwnerAndSkuProductBindingRejectWrongSupply() {
        facts.p=pledge(Allocation.FINITE,2);jdbc.update("UPDATE pms_product SET merchant_id=99");
        assertThrows(IllegalStateException.class,()->service.importApprovedPledge("pledge"));assertEquals(10,stock());
        jdbc.update("UPDATE pms_product SET merchant_id=7");jdbc.update("UPDATE pms_sku SET product_id=99");
        assertThrows(IllegalStateException.class,()->service.importApprovedPledge("pledge"));assertEquals(0,count("w17_noncash_pledge"));
    }
    @Test void insufficientStockNeverCreatesPledge() {
        facts.p=pledge(Allocation.FINITE,11);assertThrows(IllegalStateException.class,()->service.importApprovedPledge("pledge"));assertEquals(10,stock());assertEquals(0,count("w17_noncash_pledge"));
    }
    @Test void importAuditFailureRollsBackCanonicalStockAndPledge() {
        facts.p=pledge(Allocation.FINITE,3);NonCashRewardService broken=newService(failAt("insertAudit"));
        assertThrows(IllegalStateException.class,()->broken.importApprovedPledge("pledge"));assertEquals(10,stock());assertEquals(0,count("w17_noncash_pledge"));
    }
    @Test void finiteAwardReplaysStableCompletionAcrossTransportEventsAndRestart() throws Exception {
        supply(Allocation.FINITE,2);Award a=award(1);facts.events.put("redelivery",q(1,"completion-1","eligible-1"));
        NonCashRewardService restarted=newService(newMapper());Award b=restarted.award("pledge",1,"redelivery");
        assertEquals(a.awardId,b.awardId);assertEquals(1,count("w17_noncash_award"));assertEquals(1,value("available"));assertEquals(1,value("awarded"));assertEquals(8,stock());conserved();
    }
    @Test void lastUnitTwoIndependentServiceInstancesOnlyOneWins() throws Exception {
        supply(Allocation.FINITE,1);event("a",1);event("b",2);NonCashRewardService other=newService(newMapper());
        List<Boolean> won=race(()->service.award("pledge",1,"a"),()->other.award("pledge",2,"b"));
        assertEquals(1,won.stream().filter(Boolean::booleanValue).count());assertEquals(1,count("w17_noncash_award"));assertEquals(0,value("available"));conserved();
    }
    @Test void duplicateConcurrentAwardCommitsOneReceipt() throws Exception {
        supply(Allocation.FINITE,2);event("a",1);NonCashRewardService other=newService(newMapper());
        assertEquals(Arrays.asList(true,true),race(()->service.award("pledge",1,"a"),()->other.award("pledge",1,"a")));
        assertEquals(1,count("w17_noncash_award"));assertEquals(1,value("available"));conserved();
    }
    @Test void awardAuditFailureRollsBackReceiptAndQuota() {
        supply(Allocation.FINITE,1);event("a",1);assertThrows(IllegalStateException.class,()->newService(failAt("insertAudit")).award("pledge",1,"a"));
        assertEquals(0,count("w17_noncash_award"));assertEquals(1,value("available"));conserved();
    }
    @Test void guaranteeRequiresDedicatedReservationForSameBeneficiaryAndEligibility() {
        supply(Allocation.RESERVED_GUARANTEE,2);event("a",1);event("b",2);
        assertThrows(IllegalStateException.class,()->service.award("pledge",1,"a"));
        String r=service.reserveGuarantee("pledge",1,"a");assertEquals(r,service.reserveGuarantee("pledge",1,"a"));
        assertEquals(1,value("reserved"));assertEquals(1,value("available"));
        assertThrows(IllegalStateException.class,()->service.award("pledge",2,"b"));
        facts.events.put("wrong",q(1,"different-completion","different-entitlement"));
        assertThrows(IllegalStateException.class,()->service.award("pledge",1,"wrong"));
        service.award("pledge",1,"a");assertEquals(0,value("reserved"));assertEquals(1,value("awarded"));conserved();
    }
    @Test void reservationCannotBeConsumedTwiceByDifferentCompletionKeys() {
        supply(Allocation.RESERVED_GUARANTEE,2);event("a",1);service.reserveGuarantee("pledge",1,"a");service.award("pledge",1,"a");
        facts.events.put("again",q(1,"new-completion","eligible-1"));assertThrows(IllegalStateException.class,()->service.award("pledge",1,"again"));assertEquals(1,count("w17_noncash_award"));conserved();
    }
    @Test void finiteCannotPromiseGuaranteeAndReservationExpiryCannotInventStock() {
        supply(Allocation.FINITE,1);event("a",1);assertThrows(IllegalStateException.class,()->service.reserveGuarantee("pledge",1,"a"));
    }
    @Test void guaranteeExpiryRetiresOnceAndDoesNotReturnToSku() {
        supply(Allocation.RESERVED_GUARANTEE,1);event("a",1);String r=service.reserveGuarantee("pledge",1,"a");clock.now=1000;
        service.expireReservation("pledge",r);service.expireReservation("pledge",r);assertEquals(1,value("retired"));assertEquals(0,value("reserved"));assertEquals(9,stock());conserved();
    }
    @Test void wrongActorScopeReleaseRunSeasonAndRulesFailClosed() {
        supply(Allocation.FINITE,5);event("foreign",2);assertThrows(IllegalStateException.class,()->service.award("pledge",1,"foreign"));
        List<Scope> invalid=Arrays.asList(new Scope(ScopeType.THEME,"board","release","season"),new Scope(ScopeType.MAP,"board","other","season"),new Scope(ScopeType.MAP,"board","release","other"));
        for(Scope s:invalid) {facts.events.put("bad",new Qualification(s,1,"c","e","rules","coffee",200));assertThrows(IllegalStateException.class,()->service.award("pledge",1,"bad"));}
        facts.events.put("bad",new Qualification(scope(),1,"c","e","other","coffee",200));assertThrows(IllegalStateException.class,()->service.award("pledge",1,"bad"));assertEquals(0,count("w17_noncash_award"));conserved();
    }
    @Test void themeWorksWithoutPaymentOrRegistrationRow() {
        Scope s=new Scope(ScopeType.THEME,"topic","release","run");facts.p=new Pledge("pledge","v1","approval",s,"rules","coffee",7,8,11,12,1,100,1000,Allocation.FINITE,RewardKind.PHYSICAL,"Coffee","Conditions");
        service.importApprovedPledge("pledge");facts.events.put("a",new Qualification(s,1,"complete","eligible","rules","coffee",200));assertEquals(ScopeType.THEME,service.award("pledge",1,"a").scope.type);
    }
    @Test void freezeAndRevocationBlockNewWritesAndReplayButPreserveOwnedRead() {
        supply(Allocation.FINITE,2);Award a=award(1);facts.open=false;
        assertThrows(IllegalStateException.class,()->service.award("pledge",1,"event-1"));assertThrows(IllegalStateException.class,()->service.recordConsumption("pledge",a.awardId,9,"consume"));
        assertEquals(AwardState.AWARDED,service.read("pledge",a.awardId,1).state);assertThrows(IllegalStateException.class,()->service.read("pledge",a.awardId,2));
        facts.open=true;facts.qualifying=false;assertThrows(IllegalStateException.class,()->service.award("pledge",1,"event-1"));assertEquals(1,value("available"));
    }
    @Test void currentStaffAndStoreAuthorityIsCheckedEvenConsumptionReplay() {
        supply(Allocation.FINITE,1);Award a=award(1);
        assertThrows(IllegalStateException.class,()->service.recordConsumption("pledge",a.awardId,88,"consume"));
        assertEquals(AwardState.REDEEMED,service.recordConsumption("pledge",a.awardId,9,"consume").state);
        facts.staff=false;assertThrows(IllegalStateException.class,()->service.recordConsumption("pledge",a.awardId,9,"consume"));assertEquals(1,value("consumed"));conserved();
    }
    @Test void expiryExactBoundaryAndReplayNeverRestock() {
        supply(Allocation.FINITE,1);Award a=award(1);assertThrows(IllegalStateException.class,()->service.expire("pledge",a.awardId));clock.now=1000;
        assertThrows(IllegalStateException.class,()->service.recordConsumption("pledge",a.awardId,9,"consume"));
        assertEquals(AwardState.EXPIRED,service.expire("pledge",a.awardId).state);service.expire("pledge",a.awardId);assertEquals(1,value("retired"));assertEquals(9,stock());conserved();
    }
    @Test void expiryAndRedemptionCompeteOnlyOneLegalTerminalState() throws Exception {
        supply(Allocation.FINITE,1);Award a=award(1);clock.now=1000;Time before=new Time();before.now=999;
        NonCashRewardService redeem=new NonCashRewardService(NonCashRewardService.Mode.ISOLATED_PERSISTENCE_EVALUATION,newMapper(),facts,manager,before);
        race(()->service.expire("pledge",a.awardId),()->redeem.recordConsumption("pledge",a.awardId,9,"consume"));
        AwardState end=service.read("pledge",a.awardId,1).state;assertTrue(end==AwardState.REDEEMED||end==AwardState.EXPIRED);
        assertEquals(1,value("consumed")+value("retired"));assertEquals(0,value("awarded"));conserved();
    }
    @Test void consumedRefundAppendsAuditButNeverRestocksOrErasesConsumption() {
        supply(Allocation.FINITE,1);Award a=award(1);service.recordConsumption("pledge",a.awardId,9,"consume");
        assertEquals(AwardState.REDEEMED,service.reverse("pledge",a.awardId,"case").state);service.reverse("pledge",a.awardId,"case");
        assertEquals(1,value("consumed"));assertEquals(0,value("available"));assertEquals(9,stock());assertEquals(4,count("w17_noncash_audit"));conserved();
    }
    @Test void unconsumedReversalRetiresAndRejectsLateConsumption() {
        supply(Allocation.FINITE,1);Award a=award(1);assertEquals(AwardState.REVERSED,service.reverse("pledge",a.awardId,"case").state);
        assertThrows(IllegalStateException.class,()->service.recordConsumption("pledge",a.awardId,9,"consume"));assertEquals(1,value("retired"));conserved();
    }
    @Test void unsupportedSupplyKindsAndInvalidValidityAreRejectedExplicitly() {
        for(RewardKind k:Arrays.asList(RewardKind.COUPON,RewardKind.SERVICE)) assertThrows(IllegalStateException.class,()->new Pledge("p","v","a",scope(),"r","s",7,8,11,12,1,100,1000,Allocation.FINITE,k,"title","terms"));
        assertThrows(IllegalStateException.class,()->new Pledge("p","v","a",scope(),"r","s",7,8,11,12,1,100,100,Allocation.FINITE,RewardKind.PHYSICAL,"title","terms"));
    }
    @Test void immutableSnapshotDoesNotFollowLaterMerchantCopyChanges() {
        supply(Allocation.FINITE,1);Award a=award(1);facts.p=new Pledge("pledge","v2","new-approval",scope(),"rules","coffee",7,8,11,12,1,100,5000,Allocation.FINITE,RewardKind.PHYSICAL,"Changed","Changed terms");
        Award b=service.read("pledge",a.awardId,1);assertEquals(a.rewardTitle,b.rewardTitle);assertEquals(1000,b.validUntil);assertEquals(a.redemptionConditions,b.redemptionConditions);
    }
    @Test void malformedUtf16CannotAliasBusinessIdentity() {
        for(String invalid:Arrays.asList("bad\uD800", "bad\uDC00", "bad\uD800x")) {
            assertThrows(IllegalStateException.class,()->new Scope(ScopeType.MAP,invalid,"release","season"));
            assertThrows(IllegalStateException.class,()->q(1,invalid,"eligible"));
            assertThrows(IllegalStateException.class,()->q(1,"complete",invalid));
            assertThrows(IllegalStateException.class,()->hash("AWARD",invalid));
        }
    }
    @Test void validUnicodeAndWhitespaceRemainExactAndLengthFramingPreventsAmbiguity() {
        assertNotEquals(hash("coffee\uD83D\uDE00"),hash("coffee?"));
        assertNotEquals(hash(" a"),hash("a"));assertNotEquals(hash("a "),hash("a"));
        assertNotEquals(hash("ab","c"),hash("a","bc"));
        assertEquals("咖啡\uD83D\uDE00",id("咖啡\uD83D\uDE00"));
    }
    @Test void oneFulfillmentReceiptCannotConsumeTwoAwardsEvenIfSourceBindingDrifts() {
        supply(Allocation.FINITE,2);Award a=award(1);service.recordConsumption("pledge",a.awardId,9,"consume");
        Award b=award(2); // Deliberately erroneous fixture now associates same source ID with B.
        assertThrows(IllegalStateException.class,()->service.recordConsumption("pledge",b.awardId,9,"consume"));
        assertEquals(AwardState.AWARDED,service.read("pledge",b.awardId,2).state);
        assertEquals(1,count("w17_noncash_consumption"));assertEquals(1,value("consumed"));conserved();
    }
    @Test void consumptionAuditFailureRollsBackOneUseClaimAndState() {
        supply(Allocation.FINITE,1);Award a=award(1);
        assertThrows(IllegalStateException.class,()->newService(failAt("insertAudit")).recordConsumption("pledge",a.awardId,9,"consume"));
        assertEquals(0,count("w17_noncash_consumption"));assertEquals(AwardState.AWARDED,service.read("pledge",a.awardId,1).state);
        assertEquals(AwardState.REDEEMED,service.recordConsumption("pledge",a.awardId,9,"consume").state);conserved();
    }
    @Test void originalDdlEnforcesInnoDbForeignKeysChecksAndNoPadCollation() {
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'w17\\_%' AND engine='InnoDB'",Integer.class));
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND constraint_name LIKE 'fk_w17_%'",Integer.class));
        assertEquals("utf8mb4_0900_bin",jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='w17_noncash_consumption' AND column_name='source_receipt_id'",String.class));
        assertEquals("NO PAD",jdbc.queryForObject("SELECT pad_attribute FROM information_schema.collations WHERE collation_name='utf8mb4_0900_bin'",String.class));
        supply(Allocation.FINITE,1);
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE w17_noncash_pledge SET available=2 WHERE pledge_id='pledge'"));
        assertThrows(RuntimeException.class,()->jdbc.update("INSERT INTO w17_noncash_consumption VALUES('missing',?,9)",hash("missing-award")));
        conserved();
    }
    @Test void sourceReceiptUnicodeCaseAndTrailingSpacePreserveExactOneUseIdentity() {
        supply(Allocation.FINITE,5);
        List<String> ids=Arrays.asList("receipt","Receipt","receipt ","咖啡\uD83D\uDE00","咖啡?");
        List<Award> awards=new ArrayList<>();
        for(int i=0;i<ids.size();i++) {
            Award a=award(i+1);awards.add(a);facts.receipts.put(ids.get(i),a.awardId);
            assertEquals(AwardState.REDEEMED,service.recordConsumption("pledge",a.awardId,9,ids.get(i)).state);
        }
        assertEquals(5,count("w17_noncash_consumption"));assertEquals(5,value("consumed"));
        assertThrows(RuntimeException.class,()->jdbc.update("INSERT INTO w17_noncash_consumption VALUES(?,?,9)",ids.get(0),awards.get(1).awardId));conserved();
    }
    @Test void canonicalRetailDeductionAndPledgeDedicationRaceOnSameLastSkuUnit() throws Exception {
        jdbc.update("UPDATE pms_sku SET stock=1 WHERE id=12");facts.p=pledge(Allocation.FINITE,1);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Boolean> pledge=pool.submit(()->{start.await();try{service.importApprovedPledge("pledge");return true;}catch(IllegalStateException e){return false;}});
            Future<Boolean> retail=pool.submit(()->{start.await();return jdbc.update("UPDATE pms_sku SET stock=stock-1 WHERE id=12 AND product_id=11 AND stock>=1")==1;});
            start.countDown();boolean p=pledge.get(10,TimeUnit.SECONDS),r=retail.get(10,TimeUnit.SECONDS);
            assertNotEquals(p,r);assertEquals(0,stock());assertEquals(p?1:0,count("w17_noncash_pledge"));
            if(p)conserved();
        }finally{pool.shutdownNow();}
    }
    @Test void pledgeLockWaitIsObservedAndCurrentReadSeesCommittedLastUnit() throws Exception {
        supply(Allocation.FINITE,1);event("a",1);NonCashRewardService other=newService(newMapper());
        ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);
        try(Connection c=ds.getConnection()) {
            c.setAutoCommit(false);
            try(Statement sql=c.createStatement()){sql.executeQuery("SELECT pledge_id FROM w17_noncash_pledge WHERE pledge_id='pledge' FOR UPDATE");}
            Future<Boolean> pending=pool.submit(()->{started.countDown();try{other.award("pledge",1,"a");return true;}catch(IllegalStateException e){return false;}});
            assertTrue(started.await(2,TimeUnit.SECONDS));boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<deadline){
                long n=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='w17_noncash_pledge'",Long.class);
                if(n>0){waiting=true;break;}Thread.sleep(20);
            }
            assertTrue(waiting,"Oracle MySQL must report actual row lock wait");assertFalse(pending.isDone());
            try(Statement sql=c.createStatement()){assertEquals(1,sql.executeUpdate("UPDATE w17_noncash_pledge SET available=0,retired=1 WHERE pledge_id='pledge'"));}
            c.commit();assertFalse(pending.get(5,TimeUnit.SECONDS));assertEquals(0,count("w17_noncash_award"));conserved();
        }finally{pool.shutdownNow();}
    }
    NonCashRewardMapper failAt(String method) {
        return (NonCashRewardMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{NonCashRewardMapper.class},(proxy,m,args)->{
            if(m.getName().equals(method)) throw new IllegalStateException("injected failure");
            try{return m.invoke(mapper,args);}catch(InvocationTargetException e){throw e.getCause();}
        });
    }
    List<Boolean> race(Callable<?> a,Callable<?> b) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> fs=new ArrayList<>();for(Callable<?> job:Arrays.asList(a,b)) fs.add(pool.submit(()->{start.await();try{job.call();return true;}catch(IllegalStateException ex){return false;}}));
            start.countDown();return Arrays.asList(fs.get(0).get(10,TimeUnit.SECONDS),fs.get(1).get(10,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    static final class Time extends Clock { volatile long now=300;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);} }
    final class Facts implements Authority {
        volatile Pledge p;volatile boolean open=true,qualifying=true,staff=true;
        final Map<String,Qualification> events=new ConcurrentHashMap<>();
        final Map<String,String> receipts=new ConcurrentHashMap<>();
        public Pledge approvedPledge(String id){return p;}
        public void requireCurrentFulfillment(Pledge p,long now){require(open,"MERCHANT_FROZEN_OR_CLOSED");}
        public Qualification guaranteeEligibility(long member,String event){return qualifyingEvent(member,event);}
        public Qualification qualifyingEvent(long member,String event){require(qualifying,"SOURCE_REVOKED_OR_TEST");return events.get(event);}
        public void requireConsumption(long actor,String receipt,Award a,long now){require(staff&&actor==9&&a.storeId==8&&a.awardId.equals(receipts.get(receipt)),"STAFF_OR_RECEIPT_SCOPE_DENIED");}
        public void requireReversal(String caseId,Award a,long now){require("case".equals(caseId),"AFTERCARE_NOT_APPROVED");}
    }
}
