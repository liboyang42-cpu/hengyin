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
    WechatVerifiedPaymentFactServiceTest() { ReflectionTestUtils.setField(service,"mapper",rows); }

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
        service.bindNewInbox(7,"hash",first,true);
        service.bindNewInbox(7,"hash",first,false);
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.bindNewInbox(7,"hash",other,false));
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.bindNewInbox(9,"legacy",first,false));
        assertTrue(service.hasInboxLink(7,"hash",first)); assertFalse(service.hasInboxLink(7,"hash",other));
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->service.bindNewInbox(8,"different-notify",first,true));
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
    @Test void conflictAuditHasAnExplicitIndependentCommitContract() throws Exception {
        var tx=WechatVerifiedPaymentFactService.class.getMethod("recordVerified",WechatVerifiedPaymentFact.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,tx.propagation());
        assertTrue(java.util.List.of(tx.noRollbackFor()).contains(WechatVerifiedPaymentFactService.ConflictException.class));
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
