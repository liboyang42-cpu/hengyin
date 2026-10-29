package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.domain.WxPaymentCallbackInbox;
import com.chengyinhub.business.domain.WxPaymentCallbackClaim;
import com.chengyinhub.business.mapper.WxPaymentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxPaymentCallbackInboxService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WechatVerifiedPaymentFactServiceTest {
    private final WechatVerifiedFactTestSupport.MemoryMapper rows = new WechatVerifiedFactTestSupport.MemoryMapper();
    private final WechatVerifiedPaymentFactService service = new WechatVerifiedPaymentFactService();
    WechatVerifiedPaymentFactServiceTest() { ReflectionTestUtils.setField(service,"mapper",rows); WechatVerifiedFactTestSupport.wireUnitReads(service); }

    @Test void duplicateSuccessRetainsOriginalIdentityAndAllEconomicFacts() {
        var fact=payment("TX1","REG1",1000); var first=service.recordVerified(fact);
        assertEquals(first,service.recordVerified(fact)); assertEquals(1,rows.rows.size());
        assertEquals(fact,service.requireVerified(first).facts());
        assertEquals(0,rows.rejections.size());
    }
    @Test void bothProviderAndMerchantEventIdentityConflictsArePreservedWithoutOverwritingTheFirstFact() {
        Reference first=service.recordVerified(payment("TX1","REG1",1000));
        for (var conflict : new WechatVerifiedPaymentFact[]{payment("TX1","REG1",1001),
                payment("TX1","REG2",1000), payment("TX2","REG1",1000)}) {
            assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.recordVerified(conflict));
            assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.recordVerified(conflict));
        }
        assertEquals(1,rows.rows.size()); assertEquals(3,rows.rejections.size());
        assertEquals(1000,service.requireVerified(first).facts().amountFen());
        assertTrue(rows.rejections.values().stream().allMatch(r->r.get("candidate_facts")!=null));
    }
    @Test void persistedColumnsOrVerificationMarkersCannotBeReplacedWithAnUntrustedSuccess() {
        var ref=service.recordVerified(payment("TX1","REG1",1000));
        rows.rows.get(ref.id()).put("amount_fen",999L); rows.rows.get(ref.id()).put("total_fen",999L);
        assertThrows(IllegalStateException.class,()->service.requireVerified(ref));
        rows.rows.get(ref.id()).put("amount_fen",1000L); rows.rows.get(ref.id()).put("total_fen",1000L); rows.rows.get(ref.id()).put("verification_outcome","UNKNOWN");
        assertThrows(IllegalStateException.class,()->service.requireVerified(ref));
        assertThrows(IllegalStateException.class,()->service.requireVerified(new Reference(999,"b".repeat(64))));
    }
    @Test void originalPaymentRefundTransitionDoesNotChangeTheImmutableRefundSuccessIdentity() {
        var first=refund("SUCCESS"); var later=refund("REFUND");
        assertEquals(first.factHash(),later.factHash());
        assertEquals(service.recordVerified(first),service.recordVerified(later)); assertEquals(1,rows.rows.size());
        assertEquals("SUCCESS",service.requireVerified(service.recordVerified(later)).facts().originalPaymentState());
    }
    @Test void unknownMissingSuccessTimeAndNonIntegralOrInvalidEconomicInputsCannotProduceSuccessFacts() {
        assertThrows(IllegalArgumentException.class,()->new Reference(0,"a".repeat(64)));
        for (String state : new String[]{"UNKNOWN","CLOSED","PROCESSING","FAIL"}) {
            var candidate=new WechatVerifiedPaymentFact(Kind.REFUND,"merchant","wx-original","REG1","W1","RF1",
                    "WXRF1","TX1","APP","CNY",1000,500,Instant.parse("2026-10-24T00:00:00Z"),null,state);
            assertThrows(IllegalArgumentException.class,()->service.recordVerified(candidate));
        }
        var noTime=new WechatVerifiedPaymentFact(Kind.PAYMENT,"merchant","wx-original","REG1","W1","W1","TX1",
                "TX1","APP","CNY",1000,1000,null,null,"SUCCESS");
        assertThrows(IllegalArgumentException.class,()->service.recordVerified(noTime));
        assertEquals(0,rows.rows.size());
        service.recordRejectedPaymentNotification(new String[]{"private-order@example.invalid","1000","CNY"});
        String rejection=(String)rows.rejections.values().iterator().next().get("candidate_facts");
        assertFalse(rejection.contains("private-order@example.invalid")); assertTrue(rejection.contains("sha256:"));
    }
    @Test void factBindingIsPermanentAndLegacyUnboundReceiptCannotBeUpgraded() {
        var first=service.recordVerified(payment("TX1","REG1",1000));
        var other=service.recordVerified(payment("TX2","REG2",1000));
        service.bindNewInbox(7,"a".repeat(64),first,true);
        service.bindNewInbox(7,"a".repeat(64),first,false);
        service.bindNewInbox(7,"A".repeat(64),first,false);
        assertTrue(service.hasInboxLink(7,"A".repeat(64),first));
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.bindNewInbox(7,"a".repeat(64),other,false));
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.bindNewInbox(9,"b".repeat(64),first,false));
        assertTrue(service.hasInboxLink(7,"a".repeat(64),first)); assertFalse(service.hasInboxLink(7,"a".repeat(64),other));
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.bindNewInbox(8,"c".repeat(64),first,true));
        assertEquals(1,rows.links.size());
    }
    @Test void inboxChecksTheImmutableReferenceBeforeARepeatedDoneAckOrMutation() {
        var fact=service.recordVerified(payment("TX1","REG1",1000));
        var other=service.recordVerified(payment("TX2","REG2",1000));
        var mapper=mock(WxPaymentCallbackInboxMapper.class); var claims=mock(WxPaymentCallbackClaimService.class);
        var mutations=mock(WxPaymentCallbackMutationService.class); var inbox=new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(inbox,"mapper",mapper); ReflectionTestUtils.setField(inbox,"verifiedFacts",service);
        ReflectionTestUtils.setField(inbox,"claimService",claims); ReflectionTestUtils.setField(inbox,"mutationService",mutations);
        String hash=WxPaymentCallbackInboxServiceImpl.sha256Hex("PAY:TX1:SUCCESS");
        WxPaymentCallbackInbox row=new WxPaymentCallbackInbox(); row.setId(7L); row.setBizType(1); row.setBizId(11L); row.setEventType("SUCCESS");
        when(mapper.insertIgnore(any())).thenReturn(1,0); when(mapper.selectForUpdate(hash)).thenReturn(row);
        when(mapper.selectByNotifyHash(hash)).thenReturn(row); when(claims.claim(hash)).thenReturn(WxPaymentCallbackClaim.done(hash));
        assertTrue(inbox.registerVerified("PAY:TX1:SUCCESS",1,11L,"SUCCESS",fact));
        var mutation=mock(IWxPaymentCallbackInboxService.Mutation.class);
        assertTrue(inbox.processVerified("PAY:TX1:SUCCESS",fact,mutation));
        assertFalse(inbox.processVerified("PAY:TX1:SUCCESS",other,mutation));
        assertThrows(IWxPaymentCallbackInboxService.IdentityConflictException.class,
                ()->inbox.registerVerified("PAY:TX1:SUCCESS",1,11L,"SUCCESS",other));
        verify(claims,times(1)).claim(hash); verifyNoInteractions(mutations,mutation);
        assertEquals(1,rows.rejections.size());
    }
    @Test void acquiredMutationRechecksTheFactBindingInsideTheOriginalMutationBoundary() {
        var reference=service.recordVerified(payment("TX1","REG1",1000));
        String hash=WxPaymentCallbackInboxServiceImpl.sha256Hex("PAY:TX1:SUCCESS");
        service.bindNewInbox(7,hash,reference,true);
        var mapper=mock(WxPaymentCallbackInboxMapper.class);var claims=mock(WxPaymentCallbackClaimService.class);
        var mutations=mock(WxPaymentCallbackMutationService.class);var inbox=new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(inbox,"mapper",mapper);ReflectionTestUtils.setField(inbox,"verifiedFacts",service);
        ReflectionTestUtils.setField(inbox,"claimService",claims);ReflectionTestUtils.setField(inbox,"mutationService",mutations);
        var row=new WxPaymentCallbackInbox();row.setId(7L);when(mapper.selectByNotifyHash(hash)).thenReturn(row);
        var claim=WxPaymentCallbackClaim.acquired(hash,"owner",1L);when(claims.claim(hash)).thenReturn(claim);
        when(mutations.apply(eq(claim),any())).thenAnswer(call->{
            // Controlled boundary double: the production service invokes isCurrent only under its original locks/transaction.
            rows.links.get(hash).put("fact_hash","b".repeat(64));
            return call.getArgument(1,IWxPaymentCallbackInboxService.Mutation.class).isCurrent(row);
        });
        var original=mock(IWxPaymentCallbackInboxService.Mutation.class);
        assertFalse(inbox.processVerified("PAY:TX1:SUCCESS",reference,original));
        verifyNoInteractions(original);verify(claims).release(hash,"owner",1L);
    }

    @Test void conflictAuditHasAnExplicitIndependentCommitContract() {
        var commits=new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"factWriteTransactions",new org.springframework.transaction.support.AbstractPlatformTransactionManager(){
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition){
                assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getPropagationBehavior());
                assertFalse(definition.isReadOnly());
            }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){commits.incrementAndGet();}
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){fail("conflict evidence must commit before rejection");}
        });
        var first=service.recordVerified(payment("TX1","REG1",1000));
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.recordVerified(payment("TX1","REG1",1001)));
        assertEquals(2,commits.get());assertEquals(1,rows.rejections.size());assertEquals(1000,service.requireVerified(first).facts().totalFen());
        new org.springframework.transaction.support.TransactionTemplate(WechatVerifiedFactTestSupport.unitBusinessTransactions()).executeWithoutResult(status->{
            assertThrows(IllegalStateException.class,()->service.recordVerified(payment("TX1","REG1",1000)));
        });
        assertEquals(2,commits.get(),"outer owner rejected before suspending/acquiring another business lock");
    }
    @Test void lateSuccessRetainsOneOriginalManualTicketAndCompletionRequiresTheOwnersTransaction() {
        var jdbc=(org.springframework.jdbc.core.JdbcTemplate)ReflectionTestUtils.getField(service,"factIntentJdbc");
        ReflectionTestUtils.setField(service,"factIntentJdbc",new org.springframework.jdbc.core.JdbcTemplate(){
            @Override public java.util.List<Map<String,Object>> queryForList(String sql,Object... args) {
                if(sql.startsWith("SELECT closed_confirmed_at")) return java.util.List.of(Map.of("closed_confirmed_at",java.time.Instant.EPOCH));
                return jdbc.queryForList(sql,args);
            }
        });
        var candidate=payment("TX1","REG1",1000);var reference=service.recordVerified(candidate);
        assertEquals(reference,service.recordVerified(candidate));
        var ticket=service.findPaymentRecovery(reference).orElseThrow();
        assertEquals(1L,ticket.businessId());assertEquals(4,ticket.state());
        assertEquals(candidate,ticket.original().facts());assertEquals("VERIFIED_SUCCESS_AFTER_CLOSED",ticket.reason());
        assertEquals(1,rows.rows.size());assertEquals(1,rows.recoveries.size());assertEquals(1,rows.rejections.size());
        assertThrows(IllegalStateException.class,()->service.completePaymentRecovery(reference,1));
        new org.springframework.transaction.support.TransactionTemplate(WechatVerifiedFactTestSupport.unitBusinessTransactions()).executeWithoutResult(status->{
            assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.completePaymentRecovery(reference,2));
            // This unit models only the owner's boundary. Actual consumption proof remains the A-owned mapper/transaction.
            service.completePaymentRecovery(reference,1);service.completePaymentRecovery(reference,1);
        });
        assertEquals(2,service.findPaymentRecovery(reference).orElseThrow().state());
        assertEquals(reference,service.recordVerified(candidate));
        assertEquals(2,service.findPaymentRecovery(reference).orElseThrow().state(),"verified replay cannot re-open a resolved handoff");
    }

    @Test void actualLocalStateConflictQueuesOriginalSuccessButMissingBusinessNeverInventsAnOwner() {
        var owner=(com.chengyinhub.business.service.WechatPaymentOrderOwner)ReflectionTestUtils.getField(service,"factBusinessOwner");
        when(owner.lock("REG1")).thenReturn(new com.chengyinhub.business.service.WechatPaymentOrderOwner.Snapshot(
                "REGISTRATION",1L,java.math.BigDecimal.TEN,false,false));
        var reference=service.recordVerified(payment("TX1","REG1",1000));
        assertEquals("VERIFIED_SUCCESS_LOCAL_CONFLICT",service.findPaymentRecovery(reference).orElseThrow().reason());
        when(owner.lock("REG2")).thenThrow(new com.chengyinhub.business.service.WechatPaymentOrderOwner.MissingOrderException());
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.recordVerified(payment("TX2","REG2",1000)));
        assertNotNull(service.findPaymentSuccess("merchant","REG2","TX2"));
        assertEquals(1,rows.recoveries.size(),"no fabricated business id for a missing original row");
        String reason=WechatVerifiedPaymentFactService.REJECTION_VERIFIED_BUSINESS_MISSING;
        assertEquals("VERIFIED_BUSINESS_MISSING",reason);
        assertTrue(reason.matches("[A-Z_]{1,32}"),"canonical rejection_reason is VARCHAR(32) ASCII");
        assertTrue(rows.rejections.values().stream().anyMatch(r->reason.equals(r.get("rejection_reason"))));
    }

    @Test void wrongOriginalAttemptOwnerOrRequestHashIsRejectedBeforeAReferenceCanBeConsumed() {
        var jdbc=(org.springframework.jdbc.core.JdbcTemplate)ReflectionTestUtils.getField(service,"factIntentJdbc");
        ReflectionTestUtils.setField(service,"factIntentJdbc",new org.springframework.jdbc.core.JdbcTemplate(){
            @Override public java.util.List<Map<String,Object>> queryForList(String sql,Object... args) {
                if(sql.startsWith("SELECT business_out_trade_no")) return java.util.List.of(Map.of(
                        "business_out_trade_no","REG1","biz_type","REGISTRATION","biz_id",99L));
                return jdbc.queryForList(sql,args);
            }
        });
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.recordVerified(payment("TX1","REG1",1000)));
        ReflectionTestUtils.setField(service,"factIntentJdbc",jdbc);
        var wrongHash=new WechatVerifiedPaymentFact(Kind.PAYMENT,"merchant","wx-original","REG1","REG1","REG1",
                "TX1","TX1","APP","CNY",1000,1000,Instant.parse("2026-10-24T00:00:00Z"),"b".repeat(64),"SUCCESS");
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.recordVerified(wrongHash));
        assertEquals(0,rows.rows.size());assertEquals(2,rows.rejections.size());
    }

    public static WechatVerifiedPaymentFact payment(String transaction,String order,long fen) {
        return new WechatVerifiedPaymentFact(Kind.PAYMENT,"merchant","wx-original",order,order,order,transaction,transaction,
                "APP","CNY",fen,fen,Instant.parse("2026-10-24T00:00:00Z"),"a".repeat(64),"SUCCESS");
    }
    private static WechatVerifiedPaymentFact refund(String originalState) {
        return new WechatVerifiedPaymentFact(Kind.REFUND,"merchant","wx-original","REG1","W1","RF1","WXRF1","TX1",
                "APP","CNY",1000,500,Instant.parse("2026-10-24T01:00:00Z"),"a".repeat(64),originalState);
    }
}
