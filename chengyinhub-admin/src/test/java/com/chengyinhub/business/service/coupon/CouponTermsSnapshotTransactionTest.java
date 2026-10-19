package com.chengyinhub.business.service.coupon;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import com.chengyinhub.business.service.coupon.CouponTermsGrantAuthority.*;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.time.*;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full exact legacy grant service + real Spring transaction/MyBatis/H2. Not full Boot/MySQL. */
public class CouponTermsSnapshotTransactionTest {
    JdbcDataSource db; JdbcTemplate jdbc; SqlSessionTemplate sql; DataSourceTransactionManager manager;
    SmsCouponMapper coupons; SmsCouponHistoryMapper histories; CouponTermsSnapshotMapper snapshots;
    SmsCouponHistoryServiceImpl legacy; MutableClock clock; CouponTermsSnapshotService service;
    AtomicInteger authorityCalls; Function<Intent, Approval> proof;
    static class MutableClock extends Clock {
        volatile long value = 1000; public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return Instant.ofEpochMilli(value); }
    }
    @BeforeEach void setup() throws Exception {
        db = new JdbcDataSource(); db.setURL("jdbc:h2:mem:coupon_terms_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(db); manager = new DataSourceTransactionManager(db); clock = new MutableClock();
        jdbc.execute("CREATE TABLE sms_coupon (id BIGINT PRIMARY KEY, name VARCHAR(100), coupon_type INT, platform INT, amount DECIMAL(10,2), min_point DECIMAL(10,2), start_time TIMESTAMP(3), end_time TIMESTAMP(3), use_type INT, publish_count BIGINT, use_count BIGINT, receive_count BIGINT, enable_time TIMESTAMP(3), code VARCHAR(64), scope_type INT, member_id BIGINT, merchant_id BIGINT, status INT, del_flag INT, description VARCHAR(500), create_by VARCHAR(100), create_time TIMESTAMP(3), update_by VARCHAR(100), update_time TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE sms_coupon_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, coupon_id BIGINT NOT NULL, member_id BIGINT NOT NULL, coupon_code VARCHAR(100) NOT NULL, qrcode_url VARCHAR(400), get_type INT, get_time TIMESTAMP(3), use_status INT, use_time TIMESTAMP(3), order_id BIGINT, order_sn VARCHAR(100), start_time TIMESTAMP(3), end_time TIMESTAMP(3), note VARCHAR(200), del_flag INT DEFAULT 0, create_by VARCHAR(100), create_time TIMESTAMP(3), update_by VARCHAR(100), update_time TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE sms_coupon_terms_snapshot (history_id BIGINT PRIMARY KEY, coupon_id BIGINT NOT NULL, issuer_member_id BIGINT NOT NULL, merchant_id BIGINT NOT NULL, actor_member_id BIGINT NOT NULL, beneficiary_member_id BIGINT NOT NULL, nonce VARCHAR(128) NOT NULL, source_receipt_id VARCHAR(128) NOT NULL, intent_digest CHAR(64) NOT NULL, terms_json VARCHAR(8192) NOT NULL, terms_digest CHAR(64) NOT NULL, recorded_at BIGINT NOT NULL, UNIQUE(issuer_member_id,nonce), UNIQUE(source_receipt_id,beneficiary_member_id,coupon_id))");
        jdbc.update("INSERT INTO sms_coupon(id,name,coupon_type,platform,amount,min_point,start_time,end_time,use_type,publish_count,use_count,receive_count,code,scope_type,member_id,merchant_id,status,del_flag,description) VALUES(8,'Original title',1,0,5.00,10.00,?,?,0,10,0,0,'PRIVATE-DEFINITION-CODE',1,9,19,1,0,'Original conditions')",new java.sql.Timestamp(100),new java.sql.Timestamp(9000));
        Configuration config = new Configuration(new Environment("isolated",new SpringManagedTransactionFactory(),db));
        config.getTypeAliasRegistry().registerAlias("SmsCoupon",SmsCoupon.class); config.getTypeAliasRegistry().registerAlias("SmsCouponHistory",SmsCouponHistory.class);
        for (String name : new String[]{"SmsCouponMapper", "SmsCouponHistoryMapper", "CouponTermsSnapshotMapper"}) {
            String resource="/mapper/business/"+name+".xml";
            try(InputStream xml=getClass().getResourceAsStream(resource)) { assertNotNull(xml); new XMLMapperBuilder(xml,config,resource,config.getSqlFragments()).parse(); }
        }
        sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)); coupons=sql.getMapper(SmsCouponMapper.class);
        histories=sql.getMapper(SmsCouponHistoryMapper.class); snapshots=sql.getMapper(CouponTermsSnapshotMapper.class);
        legacy=new SmsCouponHistoryServiceImpl(); ReflectionTestUtils.setField(legacy,"smsCouponMapper",coupons); ReflectionTestUtils.setField(legacy,"smsCouponHistoryMapper",histories);
        authorityCalls=new AtomicInteger(); proof=i -> new Approval(i,Disposition.COMMITTED_ELIGIBLE,10000);
        service=new CouponTermsSnapshotService(CouponTermsSnapshotService.Mode.ISOLATED_REVIEW,snapshots,legacy,i -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); authorityCalls.incrementAndGet(); return proof.apply(i);
        },manager,clock);
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    Intent intent() { return intent("receipt:1","nonce:1",7); }
    Intent intent(String receipt,String nonce,long beneficiary) { return new Intent(9,19,29,beneficiary,8,receipt,nonce,CouponTermsSnapshot.fromDefinition(coupons.selectSmsCouponById(8L)).getDigest()); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    void empty() { assertEquals(0,count("sms_coupon_history")); assertEquals(0,count("sms_coupon_terms_snapshot")); assertEquals(0L,coupons.selectSmsCouponById(8L).getReceiveCount()); }
    @Test void defaultModeCannotReadOrWrite() {
        CouponTermsSnapshotService closed=new CouponTermsSnapshotService(); assertThrows(IllegalStateException.class,()->closed.recordNewGrant(intent()));
        assertThrows(IllegalStateException.class,()->closed.readOwned(7,1)); empty(); assertEquals(0,authorityCalls.get());
    }
    @Test void exactLegacyGrantRecordsHistoryStockAndRawTermsTogether() {
        CouponTermsSnapshotService.Read read=service.recordNewGrant(intent()); assertEquals(CouponTermsSnapshotService.Availability.RECORDED,read.availability);
        assertEquals("Original title",read.terms.getName()); assertEquals("Original conditions",read.terms.getDescription());
        SmsCouponHistory history=histories.selectSmsCouponHistoryById(read.historyId); assertEquals(7L,history.getMemberId()); assertEquals(8L,history.getCouponId());
        assertEquals(0,history.getGetType()); assertEquals(0,history.getUseStatus()); assertEquals(new Date(100),history.getStartTime()); assertEquals(new Date(9000),history.getEndTime());
        assertEquals(1L,coupons.selectSmsCouponById(8L).getReceiveCount()); assertEquals(1,count("sms_coupon_terms_snapshot"));
        assertEquals(29L,snapshots.byNonce(9,"nonce:1").actorMemberId); assertFalse(read.terms.getJson().contains("PRIVATE-DEFINITION-CODE"));
    }
    @Test void parentTermsEditsCannotRewriteRecordedRead() {
        CouponTermsSnapshotService.Read first=service.recordNewGrant(intent()); jdbc.update("UPDATE sms_coupon SET name='New', description='Changed', end_time=? WHERE id=8",new java.sql.Timestamp(15000));
        CouponTermsSnapshotService.Read read=service.readOwned(7,first.historyId); assertEquals(first.terms.getJson(),read.terms.getJson()); assertEquals(9000L,read.terms.getEndTime());
    }
    @Test void legacyHistoryIsExplicitlyUnknownWithoutBackfill() {
        long id=legacy.grantCouponWithReceipt(7L,coupons.selectSmsCouponById(8L));
        assertEquals(CouponTermsSnapshotService.Availability.NOT_PROVIDED,service.readOwned(7,id).availability); assertNull(service.readOwned(7,id).terms); assertEquals(0,count("sms_coupon_terms_snapshot"));
    }
    @Test void foreignOwnerCannotReadSnapshotOrLegacyHistory() {
        long id=service.recordNewGrant(intent()).historyId; assertNull(service.readOwned(17,id)); assertNull(service.readOwned(7,id+1));
    }
    @Test void missingCommittedReceiptCannotConstructIntent() {
        assertThrows(IllegalArgumentException.class,()->new Intent(9,19,29,7,8,null,"nonce",intent().disclosedTermsDigest)); empty();
    }
    @Test void absentAuthorityHasZeroGrant() { proof=i -> null; assertThrows(IllegalStateException.class,()->service.recordNewGrant(intent())); empty(); }
    @Test void evidenceOnlyCannotGrant() { proof=i -> new Approval(i,Disposition.EVIDENCE_ONLY,10000); assertThrows(IllegalStateException.class,()->service.recordNewGrant(intent())); empty(); }
    @Test void fallbackCompletedCannotGrant() { proof=i -> new Approval(i,Disposition.FALLBACK_COMPLETED,10000); assertThrows(IllegalStateException.class,()->service.recordNewGrant(intent())); empty(); }
    @Test void actorCannotBeConfusedWithBeneficiary() {
        Intent requested=intent(); proof=i -> new Approval(new Intent(9,19,7,29,8,i.sourceReceiptId,i.nonce,i.disclosedTermsDigest),Disposition.COMMITTED_ELIGIBLE,10000);
        assertThrows(IllegalStateException.class,()->service.recordNewGrant(requested)); empty();
    }
    @Test void wrongTenantDefinitionHasZeroGrant() { Intent i=intent(); Intent wrong=new Intent(10,19,29,7,8,i.sourceReceiptId,i.nonce,i.disclosedTermsDigest); assertThrows(IllegalStateException.class,()->service.recordNewGrant(wrong)); empty(); }
    @Test void wrongMerchantDefinitionHasZeroGrant() { Intent i=intent(); Intent wrong=new Intent(9,20,29,7,8,i.sourceReceiptId,i.nonce,i.disclosedTermsDigest); assertThrows(IllegalStateException.class,()->service.recordNewGrant(wrong)); empty(); }
    @Test void deletedDefinitionCannotGrant() { Intent i=intent(); jdbc.update("UPDATE sms_coupon SET del_flag=2 WHERE id=8"); assertThrows(IllegalStateException.class,()->service.recordNewGrant(i)); empty(); }
    @Test void manualInvalidAndStoppedReuseActualLegacyRefusal() {
        for(int status:new int[]{3,4}) { jdbc.update("UPDATE sms_coupon SET status=? WHERE id=8",status); assertThrows(IllegalStateException.class,()->service.recordNewGrant(intent())); empty(); }
    }
    @Test void exhaustedFiniteStockCannotCreateHistoryOrSnapshot() { jdbc.update("UPDATE sms_coupon SET publish_count=1,receive_count=1 WHERE id=8"); assertThrows(IllegalStateException.class,()->service.recordNewGrant(intent())); assertEquals(0,count("sms_coupon_history")); assertEquals(0,count("sms_coupon_terms_snapshot")); assertEquals(1L,coupons.selectSmsCouponById(8L).getReceiveCount()); }
    @Test void snapshotConstraintFailureRollsBackLegacyHistoryAndStock() { jdbc.execute("ALTER TABLE sms_coupon_terms_snapshot ADD CONSTRAINT reject_snapshot CHECK(history_id<0)"); assertThrows(RuntimeException.class,()->service.recordNewGrant(intent())); empty(); }
    @Test void historyConstraintFailureRollsBackStockIncrement() { jdbc.execute("ALTER TABLE sms_coupon_history ADD CONSTRAINT reject_history CHECK(coupon_id<0)"); assertThrows(RuntimeException.class,()->service.recordNewGrant(intent())); empty(); }
    @Test void authorityRevokedAfterBothInsertsRollsBackEverything() { proof=i -> authorityCalls.get()>=3 ? null : new Approval(i,Disposition.COMMITTED_ELIGIBLE,10000); assertThrows(IllegalStateException.class,()->service.recordNewGrant(intent())); assertEquals(3,authorityCalls.get()); empty(); }
    @Test void authorityClockAdvanceAtSecondCheckCannotUseOldTime() { proof=i -> { if(authorityCalls.get()==2) clock.value=10000; return new Approval(i,Disposition.COMMITTED_ELIGIBLE,10000); }; assertThrows(IllegalStateException.class,()->service.recordNewGrant(intent())); empty(); }
    @Test void sourceTransactionMustAlreadyBeCommitted() { new TransactionTemplate(manager).execute(s -> { assertThrows(IllegalStateException.class,()->service.recordNewGrant(intent())); return null; }); empty(); assertEquals(0,authorityCalls.get()); }
    @Test void changedDefinitionSinceDisclosureRejectsWithoutGrant() { Intent disclosed=intent(); jdbc.update("UPDATE sms_coupon SET description='changed before lock' WHERE id=8"); assertThrows(IllegalStateException.class,()->service.recordNewGrant(disclosed)); empty(); }
    @Test void nonceReplayAfterDefinitionChangeReturnsSameHistoryAndSnapshot() {
        Intent i=intent(); CouponTermsSnapshotService.Read first=service.recordNewGrant(i); jdbc.update("UPDATE sms_coupon SET description='later terms' WHERE id=8");
        CouponTermsSnapshotService.Read replay=service.recordNewGrant(i); assertEquals(first.historyId,replay.historyId); assertEquals(first.terms.getJson(),replay.terms.getJson()); assertEquals(1,count("sms_coupon_history")); assertEquals(1L,coupons.selectSmsCouponById(8L).getReceiveCount());
    }
    @Test void nonceRebindingBeneficiaryIsRejected() { service.recordNewGrant(intent()); assertThrows(IllegalStateException.class,()->service.recordNewGrant(intent("receipt:1","nonce:1",17))); assertEquals(1,count("sms_coupon_history")); }
    @Test void sameSourceUnderNewNonceCannotIssueAgain() { service.recordNewGrant(intent()); assertThrows(IllegalStateException.class,()->service.recordNewGrant(intent("receipt:1","nonce:2",7))); assertEquals(1,count("sms_coupon_history")); }
    @Test void replayStillRequiresCurrentAuthority() { Intent i=intent(); service.recordNewGrant(i); proof=x -> null; assertThrows(IllegalStateException.class,()->service.recordNewGrant(i)); assertEquals(1,count("sms_coupon_history")); }
    @Test void nullableValidityStaysUnknownRatherThanInvented() { jdbc.update("UPDATE sms_coupon SET start_time=NULL,end_time=NULL WHERE id=8"); CouponTermsSnapshotService.Read r=service.recordNewGrant(intent()); assertNull(r.terms.getStartTime()); assertNull(r.terms.getEndTime()); assertEquals(r.terms.getJson(),service.readOwned(7,r.historyId).terms.getJson()); }
    @Test void snapshotTamperingFailsClosed() { long id=service.recordNewGrant(intent()).historyId; jdbc.update("UPDATE sms_coupon_terms_snapshot SET terms_json='{}' WHERE history_id=?",id); assertThrows(IllegalStateException.class,()->service.readOwned(7,id)); }
    @Test void actorCorrelationTamperingFailsClosed() { long id=service.recordNewGrant(intent()).historyId; jdbc.update("UPDATE sms_coupon_terms_snapshot SET actor_member_id=7 WHERE history_id=?",id); assertThrows(IllegalStateException.class,()->service.readOwned(7,id)); }
    @Test void historyDateTamperingCannotBePresentedAsFrozenSnapshot() { long id=service.recordNewGrant(intent()).historyId; jdbc.update("UPDATE sms_coupon_history SET end_time=? WHERE id=?",new java.sql.Timestamp(15000),id); assertThrows(IllegalStateException.class,()->service.readOwned(7,id)); }
    @Test void sameNonceConcurrentCallsReturnOneStoredSnapshot() throws Exception {
        Intent i=intent(); ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try { Future<CouponTermsSnapshotService.Read> a=pool.submit(()->{start.await();return service.recordNewGrant(i);}); Future<CouponTermsSnapshotService.Read>b=pool.submit(()->{start.await();return service.recordNewGrant(i);}); start.countDown();
            CouponTermsSnapshotService.Read x=a.get(10,TimeUnit.SECONDS),y=b.get(10,TimeUnit.SECONDS); assertEquals(x.historyId,y.historyId); assertEquals(x.terms.getJson(),y.terms.getJson()); assertEquals(1,count("sms_coupon_history")); assertEquals(1L,coupons.selectSmsCouponById(8L).getReceiveCount());
        } finally { pool.shutdownNow(); }
    }
    @Test void definitionEditWaitingOnGrantLockCannotChangeIssuedSnapshot() throws Exception {
        Intent i=intent(); CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),editorStarted=new CountDownLatch(1);
        proof=x -> { if(authorityCalls.get()==2) { locked.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e){ throw new RuntimeException(e); } } return new Approval(x,Disposition.COMMITTED_ELIGIBLE,10000); };
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try { Future<CouponTermsSnapshotService.Read> grant=pool.submit(()->service.recordNewGrant(i)); assertTrue(locked.await(5,TimeUnit.SECONDS));
            Future<Integer> edit=pool.submit(()->{editorStarted.countDown(); return jdbc.update("UPDATE sms_coupon SET description='Concurrent new terms' WHERE id=8");}); assertTrue(editorStarted.await(5,TimeUnit.SECONDS)); release.countDown();
            CouponTermsSnapshotService.Read stored=grant.get(10,TimeUnit.SECONDS); assertEquals(1,edit.get(10,TimeUnit.SECONDS)); assertEquals("Original conditions",stored.terms.getDescription()); assertEquals("Concurrent new terms",coupons.selectSmsCouponById(8L).getDescription()); assertEquals(stored.terms.getJson(),service.readOwned(7,stored.historyId).terms.getJson());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void definitionEditCommittedBeforeLockFailsDisclosureEvenAfterWaiting() throws Exception {
        Intent i=intent(); ExecutorService pool=Executors.newSingleThreadExecutor();
        try(Connection c=db.getConnection()) { c.setAutoCommit(false); try(Statement st=c.createStatement()){st.executeUpdate("UPDATE sms_coupon SET description='New before grant' WHERE id=8");}
            Future<CouponTermsSnapshotService.Read> grant=pool.submit(()->service.recordNewGrant(i)); c.commit(); assertThrows(ExecutionException.class,()->grant.get(10,TimeUnit.SECONDS)); empty();
        } finally { pool.shutdownNow(); }
    }
    @Test void snapshotDefensivelyCopiesMutableDefinitionDatesAndText() {
        SmsCoupon definition=coupons.selectSmsCouponById(8L); CouponTermsSnapshot frozen=CouponTermsSnapshot.fromDefinition(definition);
        definition.getEndTime().setTime(999999); definition.setDescription("mutated Java object");
        assertEquals(9000L,frozen.getEndTime()); assertEquals("Original conditions",frozen.getDescription());
    }
    @Test void unknownSnapshotVersionWithMatchingDigestIsRejected() {
        String json=CouponTermsSnapshot.fromDefinition(coupons.selectSmsCouponById(8L)).getJson().replace("\"version\":1","\"version\":2");
        assertThrows(IllegalStateException.class,()->CouponTermsSnapshot.recorded(json,CouponTermsSnapshot.sha256(json)));
    }
    @Test void duplicateOrExtraSnapshotFieldsAreNotSilentlyNormalized() {
        String original=CouponTermsSnapshot.fromDefinition(coupons.selectSmsCouponById(8L)).getJson();
        for(String suffix:new String[]{",\"version\":1}",",\"inventedEligibility\":true}"}) {
            String changed=original.substring(0,original.length()-1)+suffix;
            assertThrows(IllegalStateException.class,()->CouponTermsSnapshot.recorded(changed,CouponTermsSnapshot.sha256(changed)));
        }
    }
    @Test void replayCannotRebindActorWhileKeepingBeneficiary() {
        Intent i=intent(); service.recordNewGrant(i);
        Intent otherActor=new Intent(9,19,39,7,8,i.sourceReceiptId,i.nonce,i.disclosedTermsDigest);
        assertThrows(IllegalStateException.class,()->service.recordNewGrant(otherActor)); assertEquals(1,count("sms_coupon_history"));
    }
    @Test void actualDefinitionWriteIsBlockedWhileAuthorityHoldsGrantTransaction() throws Exception {
        Intent i=intent(); CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        proof=x -> { if(authorityCalls.get()==2) {locked.countDown();try {assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}} return new Approval(x,Disposition.COMMITTED_ELIGIBLE,10000); };
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try { Future<CouponTermsSnapshotService.Read> grant=pool.submit(()->service.recordNewGrant(i)); assertTrue(locked.await(5,TimeUnit.SECONDS));
            try(Connection c=db.getConnection();Statement update=c.createStatement()) {
                update.execute("SET LOCK_TIMEOUT 100");
                java.sql.SQLException blocked=assertThrows(java.sql.SQLException.class,()->update.executeUpdate("UPDATE sms_coupon SET description='After lock' WHERE id=8"));
                System.out.println("Observed real H2 definition-lock rejection SQLState="+blocked.getSQLState()+" code="+blocked.getErrorCode());
                release.countDown(); CouponTermsSnapshotService.Read recorded=grant.get(10,TimeUnit.SECONDS);
                assertEquals(1,update.executeUpdate("UPDATE sms_coupon SET description='After lock' WHERE id=8"));
                assertEquals("Original conditions",service.readOwned(7,recorded.historyId).terms.getDescription());
            }
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test void concurrentDifferentSourcesCannotExceedActualFinalStock() throws Exception {
        jdbc.update("UPDATE sms_coupon SET publish_count=1 WHERE id=8"); Intent a=intent("source:a","nonce:a",7),b=intent("source:b","nonce:b",17);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);int success=0,refused=0;
        try { Future<?> x=pool.submit(()->{start.await();return service.recordNewGrant(a);}),y=pool.submit(()->{start.await();return service.recordNewGrant(b);});start.countDown();
            for(Future<?> f:new Future<?>[]{x,y}) {try{f.get(10,TimeUnit.SECONDS);success++;}catch(ExecutionException e){assertTrue(e.getCause() instanceof IllegalStateException);refused++;}}
            assertEquals(1,success);assertEquals(1,refused);assertEquals(1,count("sms_coupon_history"));assertEquals(1,count("sms_coupon_terms_snapshot"));assertEquals(1L,coupons.selectSmsCouponById(8L).getReceiveCount());
        } finally {pool.shutdownNow();}
    }
    @Test void nonceCaseRemainsExactInsteadOfBorrowingAnotherSource() {
        long a=service.recordNewGrant(intent("source:A","nonce:A",7)).historyId,b=service.recordNewGrant(intent("source:a","nonce:a",7)).historyId;
        assertNotEquals(a,b);assertEquals(2,count("sms_coupon_terms_snapshot"));
    }
    @Test void rawUnicodeAndNullableMetadataRoundTripWithoutRightsInference() {
        jdbc.update("UPDATE sms_coupon SET name=?,description=NULL WHERE id=8","原始 \"条款\"\n尾行");
        CouponTermsSnapshotService.Read recorded=service.recordNewGrant(intent());
        assertEquals("原始 \"条款\"\n尾行",recorded.terms.getName());assertNull(recorded.terms.getDescription());
        assertEquals(recorded.terms.getJson(),service.readOwned(7,recorded.historyId).terms.getJson());
    }
    private void assertNoStackExhaustion(Throwable rejection) {
        Throwable current=rejection;int depth=0;boolean stack=false;StringBuilder chain=new StringBuilder();
        while(current!=null && depth++<12){chain.append(current.getClass().getName()).append(" -> ");stack|=current instanceof StackOverflowError;current=current.getCause();}
        System.out.println("Deep recorded terms rejection cause chain: "+chain);
        assertFalse(stack,"Rejection must happen before stack exhaustion, even when Jackson wraps the Error");
    }
    private String deepUnknownArray(int depth) {
        StringBuilder json=new StringBuilder("{\"unknown\":");
        for(int i=0;i<depth;i++)json.append('[');json.append('0');for(int i=0;i<depth;i++)json.append(']');return json.append('}').toString();
    }
    @Test void hashMatchedDeepUnknownArraysFailBoundedlyBeforeTreeMaterialization() {
        String json=deepUnknownArray(3000);assertTrue(json.length()<8192);
        IllegalStateException rejected=assertThrows(IllegalStateException.class,()->CouponTermsSnapshot.recorded(json,CouponTermsSnapshot.sha256(json)));
        assertNoStackExhaustion(rejected);assertEquals("Snapshot nesting exceeds limit",rejected.getMessage());
    }
    @Test void ownedReadRejectsHashMatchedDeepUnknownTermsWithoutStackOverflow() {
        long id=service.recordNewGrant(intent()).historyId;String json=deepUnknownArray(3000),digest=CouponTermsSnapshot.sha256(json);
        Intent correlated=new Intent(9,19,29,7,8,"receipt:1","nonce:1",digest);
        jdbc.update("UPDATE sms_coupon_terms_snapshot SET terms_json=?,terms_digest=?,intent_digest=? WHERE history_id=?",json,digest,correlated.digest(),id);
        IllegalStateException rejected=assertThrows(IllegalStateException.class,()->service.readOwned(7,id));
        assertNoStackExhaustion(rejected);assertEquals("Snapshot nesting exceeds limit",rejected.getMessage());
        assertEquals(1,count("sms_coupon_history"));assertEquals(1L,coupons.selectSmsCouponById(8L).getReceiveCount());
    }
    @Test void legalBracketsInsideRecordedStringsAreNotStructuralDepth() {
        String brackets="[[[[[[[[{{{{{{}}}}}}]]]]]]]]";
        jdbc.update("UPDATE sms_coupon SET name=?,description=? WHERE id=8",brackets,brackets+" \"quoted\" \\ path");
        CouponTermsSnapshotService.Read r=service.recordNewGrant(intent());assertEquals(brackets,r.terms.getName());
        assertEquals(r.terms.getJson(),service.readOwned(7,r.historyId).terms.getJson());
    }
    @Test void duplicateKeysAreRejectedWithoutNormalizingTree() {
        String json="{\"version\":1,\"version\":1}";
        IllegalStateException rejected=assertThrows(IllegalStateException.class,()->CouponTermsSnapshot.recorded(json,CouponTermsSnapshot.sha256(json)));
        assertTrue(rejected.getCause() instanceof com.fasterxml.jackson.core.JsonParseException);
    }
    @Test void trailingSecondRootIsRejectedBeforeTree() {
        String json=CouponTermsSnapshot.fromDefinition(coupons.selectSmsCouponById(8L)).getJson()+"{}";
        IllegalStateException rejected=assertThrows(IllegalStateException.class,()->CouponTermsSnapshot.recorded(json,CouponTermsSnapshot.sha256(json)));
        assertEquals("Trailing snapshot tokens",rejected.getMessage());
    }
    @Test void broadUnknownObjectIsRejectedWithinTokenBudget() {
        StringBuilder json=new StringBuilder("{");for(int i=0;i<100;i++){if(i>0)json.append(',');json.append('\"').append(i).append("\":0");}json.append('}');
        String value=json.toString();assertTrue(value.length()<8192);
        IllegalStateException rejected=assertThrows(IllegalStateException.class,()->CouponTermsSnapshot.recorded(value,CouponTermsSnapshot.sha256(value)));
        assertEquals("Snapshot token limit exceeded",rejected.getMessage());
    }
}
