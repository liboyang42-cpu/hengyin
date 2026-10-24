package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit contracts with mapper/B-reader doubles. Transaction flags here do NOT prove rollback or MySQL. */
class CoopSettlementOriginalLedgerTest {
    final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    final CoopSettlementCashEvidenceMapper evidence = mock(CoopSettlementCashEvidenceMapper.class);
    final CoopSettlementOriginalMapper originals = mock(CoopSettlementOriginalMapper.class);
    final CoopSettlementMapper heads = mock(CoopSettlementMapper.class);
    final CoopSettlementAdjustmentMapper legacyAdjustments = mock(CoopSettlementAdjustmentMapper.class);
    final WechatVerifiedPaymentFactService verified = mock(WechatVerifiedPaymentFactService.class);
    final com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService paymentConsumptions = mock(com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService.class);
    final UmsMemberMapper members = mock(UmsMemberMapper.class);
    final IUmsMemberBalanceDetailService ledger = mock(IUmsMemberBalanceDetailService.class);
    final UmsMemberSettlementDebtMapper debtRows = mock(UmsMemberSettlementDebtMapper.class);
    final SettlementDebtLedger debts = spy(new SettlementDebtLedger(debtRows, members, mock(UmsMemberBalanceDetailMapper.class)));
    final CoopSettlementOriginalLedger owner = new CoopSettlementOriginalLedger(topics,evidence,originals,heads,legacyAdjustments,verified,paymentConsumptions,members,ledger,debts);
    final List<CoopSettlementOriginalAdjustment> applied = new ArrayList<>();
    final CoopSettlementRefundReceipt[] receipt = new CoopSettlementRefundReceipt[1];
    CoopSettlementCashClose close;
    CoopSettlementCashContribution source;
    RefundApplication refund;
    CoopOriginalRefundScope scope;
    WechatVerifiedPaymentFact payment, refundFact;
    WechatVerifiedPaymentFact.Reference paymentReference, refundReference;
    CoopSettlement platform, initiator;
    List<CoopSettlementOriginalComponent> components;

    @BeforeEach void arrangeOriginalFrozenOrder() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        ReflectionTestUtils.setField(owner,"originalCloseEvidence",mock(CoopSettlementEvidenceMapper.class));
        CmsTopic topic = new CmsTopic(); topic.setId(11L); topic.setIsSettlement(1);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenReturn(topic);
        close = new CoopSettlementCashClose(); close.setId(1L); close.setTopicId(11L);
        close.setOriginCalculationVersion("ORIGINAL_EXACT_SINGLE_ORDER_V1");
        close.setOriginalContributionCount(1); close.setOriginalSalesBasis(money("10"));
        when(evidence.selectCloseForUpdate(11L)).thenReturn(close);
        source = new CoopSettlementCashContribution(); source.setCashCloseId(1L); source.setSourceTopicId(11L);
        source.setRegistrationId(1L); source.setPayerRegistrationId(1L); source.setPayerOrderNo("REG1");
        source.setRegistrationNo("REG1"); source.setSalesBasisAmount(money("10"));
        when(evidence.selectContributionForUpdate(1L)).thenReturn(source);
        scope = new CoopOriginalRefundScope(); scope.setRefundId(901L); scope.setSourceType("registration"); scope.setSourceId(1L);
        scope.setRegistrationId(1L); scope.setOwnerType(1L); scope.setOwnerId(11L); scope.setTopicId(11L); scope.setRelationPresent(true);
        when(originals.selectRefundScope(901L)).thenReturn(scope); when(originals.selectRefundScopeForUpdate(901L)).thenReturn(scope);
        refund = new RefundApplication(); refund.setId(901L); refund.setRefundNo("RF901"); refund.setSourceType("registration"); refund.setSourceId(1L);
        refund.setStatus(1); refund.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS); refund.setRefundAmount(money("10"));
        when(originals.selectRefundForUpdate(901L)).thenReturn(refund);
        payment = payment(); refundFact = refundFact("wx-original");
        paymentReference = new WechatVerifiedPaymentFact.Reference(31L, payment.factHash());
        refundReference = new WechatVerifiedPaymentFact.Reference(41L, refundFact.factHash());
        when(verified.requireVerified(paymentReference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(paymentReference,payment));
        when(verified.requireVerified(refundReference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(refundReference,refundFact));
        when(originals.selectRefundVerifiedReferenceForUpdate(901L)).thenReturn(refundReference);
        platform = head(11L,"PLATFORM",null,"1",1); initiator = head(12L,"INITIATOR_MARGIN",7L,"9",0);
        when(heads.selectByIdForUpdate(11L)).thenReturn(platform); when(heads.selectByIdForUpdate(12L)).thenReturn(initiator);
        when(originals.selectTopicHeadsForUpdate(11L)).thenReturn(List.of(platform,initiator));
        components = new ArrayList<>(List.of(component(101L,platform,"ACCOUNTING"),component(102L,initiator,"MEMBER")));
        when(originals.selectComponentsForPayerForUpdate(1L,1L)).thenReturn(components);
        when(originals.selectReceiptsForPayerForUpdate(1L,1L)).thenReturn(List.of());
        when(originals.insertReceipt(any())).thenAnswer(call -> { receipt[0]=call.getArgument(0); receipt[0].setId(1001L); return 1; });
        when(originals.insertAdjustment(any())).thenAnswer(call -> { CoopSettlementOriginalAdjustment row=call.getArgument(0); row.setId(2001L+applied.size()); applied.add(row); return 1; });
        when(originals.selectAdjustmentsForReceiptForUpdate(1001L)).thenAnswer(call -> new ArrayList<>(applied));
        when(debtRows.insertIgnore(any())).thenReturn(1);
    }
    @AfterEach void clearTransactionFlags() { TransactionSynchronizationManager.clear(); }

    @Test void originalExactFullRefundReversesAllOriginalHeadsOnceWithoutDebitingUnpaidMember() {
        owner.reverseCompletedRegistrationRefund(901L);
        assertEquals(2,applied.size()); assertEquals(money("10"),receipt[0].getReversedAmount());
        assertEquals(money("-10"),applied.stream().map(CoopSettlementOriginalAdjustment::getAmount).reduce(money("0"),BigDecimal::add));
        assertEquals(money("1"),platform.getAmount()); assertEquals(money("9"),initiator.getAmount());
        verifyNoInteractions(members,ledger,debtRows);
        when(originals.selectReceiptForUpdate(901L)).thenReturn(receipt[0]);
        owner.reverseCompletedRegistrationRefund(901L);
        verify(originals,times(1)).insertReceipt(any()); verify(originals,times(2)).insertAdjustment(any());
        assertEquals("WX",receipt[0].getVerifiedFactKind()); assertEquals(refundReference.hash(),receipt[0].getVerifiedFactHash());
    }

    @Test void paidHeadRecoversOnlyAvailableBalanceAndRecordsOriginalAdjustmentDebt() {
        initiator.setStatus(1); when(members.selectBalanceForUpdate(7L)).thenReturn(money("2"));
        when(originals.selectHeadCreditAmountsForUpdate(12L,7L,11L)).thenReturn(List.of(money("9")));
        when(members.deductBalance(7L,money("2"))).thenReturn(1); when(ledger.insertUmsMemberBalanceDetail(any())).thenReturn(1);
        owner.reverseCompletedRegistrationRefund(901L);
        CoopSettlementOriginalAdjustment member=applied.stream().filter(row->row.getSettlementId().equals(12L)).findFirst().orElseThrow();
        assertEquals(money("2"),member.getWalletDebit()); assertEquals(money("7"),member.getDebtAmount());
        verify(debts).recordOriginalRefund(7L,member.getId(),money("7"));
        verify(members).deductBalance(7L,money("2")); verify(ledger).insertUmsMemberBalanceDetail(argThat(flow ->
                flow.getEventType()==94L && flow.getEventId().equals(member.getId()) && flow.getChangeBalance().compareTo(money("-2"))==0));
    }

    @Test void refundFactFromAnotherAppCannotChangeAnyReceiptOrWallet() {
        WechatVerifiedPaymentFact wrong=refundFact("wx-other");
        var ref=new WechatVerifiedPaymentFact.Reference(42L,wrong.factHash());
        when(originals.selectRefundVerifiedReferenceForUpdate(901L)).thenReturn(ref);
        when(verified.requireVerified(ref)).thenReturn(new WechatVerifiedPaymentFactService.Stored(ref,wrong));
        IllegalStateException error=assertThrows(IllegalStateException.class,()->owner.reverseCompletedRegistrationRefund(901L));
        assertEquals("VERIFIED_REFUND_ORIGINAL_IDENTITY_CONFLICT",error.getMessage());
        verify(originals,never()).insertReceipt(any()); verifyNoInteractions(members,ledger,debtRows);
    }

    @Test void missingBoundVerifiedRefundReferenceCannotBeReplacedByLocalSuccessFlag() {
        when(originals.selectRefundVerifiedReferenceForUpdate(901L)).thenReturn(null);
        assertEquals("VERIFIED_REFUND_INBOX_LINK_REQUIRED",assertThrows(IllegalStateException.class,
                ()->owner.reverseCompletedRegistrationRefund(901L)).getMessage());
        verify(originals,never()).insertReceipt(any()); verifyNoInteractions(members,ledger,debtRows);
    }

    @Test void distinctManualReviewReceiptUsesExistingManualTerminalAndNeverPretendsWechatFact() {
        refund.setPayoutStatus(RefundApplication.PAYOUT_MANUAL_VERIFIED); refund.setPayoutHandlerId(71L); refund.setPayoutReviewer2Id(72L);
        refund.setPayoutReceiptUrl("/controlled/refund-receipt"); refund.setPayoutMethod("bank");
        refund.setPayoutHandledAt(new Date(1_000)); refund.setPayoutReviewed2At(new Date(2_000));
        owner.reverseCompletedRegistrationRefund(901L);
        assertEquals("MANUAL",receipt[0].getVerifiedFactKind()); assertEquals(901L,receipt[0].getVerifiedFactId());
        verify(originals,never()).selectRefundVerifiedReferenceForUpdate(anyLong());
        verify(verified,never()).requireVerified(refundReference);
        assertEquals(2,applied.size()); verifyNoInteractions(members,ledger,debtRows);
    }

    @Test void sameActorManualReceiptAndMissingActivityRelationAreRejectedBeforeEffects() {
        refund.setPayoutStatus(RefundApplication.PAYOUT_MANUAL_VERIFIED); refund.setPayoutHandlerId(71L); refund.setPayoutReviewer2Id(71L);
        assertEquals("MANUAL_ORIGINAL_TWO_PERSON_RECEIPT_REQUIRED",assertThrows(IllegalStateException.class,
                ()->owner.reverseCompletedRegistrationRefund(901L)).getMessage());
        scope.setOwnerType(2L); scope.setOwnerId(11L); scope.setTopicId(null); scope.setRelationPresent(false);
        assertEquals("ORIGINAL_REFUND_RELATION_UNREADABLE",assertThrows(IllegalStateException.class,
                ()->owner.reverseCompletedRegistrationRefund(901L)).getMessage());
        verify(originals,never()).insertReceipt(any()); verifyNoInteractions(members,ledger,debtRows);
    }

    @Test void missingOuterTransactionCannotCreateOrReverseOriginals() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertEquals("ORIGINAL_FUNDS_TRANSACTION_REQUIRED",assertThrows(IllegalStateException.class,
                ()->owner.reverseCompletedRegistrationRefund(901L)).getMessage());
        assertThrows(IllegalStateException.class,()->owner.freezeOriginals(close,List.of(),List.of()));
        verifyNoInteractions(originals,evidence,members,ledger,debtRows);
    }

    @Test void negativePendingPayoutHeadIsNotMarkedPaidOrTurnedIntoNewDebt() {
        CmsTopicServiceImpl payout=new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(payout,"cmsTopicMapper",topics); ReflectionTestUtils.setField(payout,"coopSettlementMapper",heads);
        ReflectionTestUtils.setField(payout,"coopSettlementOriginalMapper",originals);
        ReflectionTestUtils.setField(payout,"coopSettlementAdjustmentMapper",legacyAdjustments);
        CoopComplaintMapper complaints=mock(CoopComplaintMapper.class); ReflectionTestUtils.setField(payout,"coopComplaintMapper",complaints);
        ReflectionTestUtils.setField(payout,"umsMemberMapper",members);
        Date now=new Date(); when(heads.selectDuePayoutForUpdate(12L,now)).thenReturn(initiator);
        CoopSettlementOriginalAdjustment negative=new CoopSettlementOriginalAdjustment(); negative.setSettlementId(12L); negative.setAmount(money("-10"));
        when(originals.selectAdjustmentsForSettlementForUpdate(12L)).thenReturn(List.of(negative));
        assertTrue(assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(payout,
                "payoutOneDueCoopSettlement",12L,11L,now)).getMessage().contains("NEGATIVE_SETTLEMENT_HEAD_POLICY_REQUIRED"));
        verify(heads,never()).markPaidIfPending(anyLong(),any()); verifyNoInteractions(members,ledger,debtRows);
    }

    @Test void negativeOtherHeadBlocksPayoutWithoutChargingAnotherReceiver() {
        CmsTopicServiceImpl payout=new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(payout,"cmsTopicMapper",topics); ReflectionTestUtils.setField(payout,"coopSettlementMapper",heads);
        ReflectionTestUtils.setField(payout,"coopSettlementOriginalMapper",originals);
        ReflectionTestUtils.setField(payout,"coopSettlementAdjustmentMapper",legacyAdjustments);
        CoopSettlement negative=head(13L,"INITIATOR_MARGIN",8L,"-1",0);
        when(originals.selectTopicHeadsForUpdate(11L)).thenReturn(List.of(platform,initiator,negative));
        assertTrue(assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(payout,
                "payoutOneDueCoopSettlement",12L,11L,new Date())).getMessage().contains("NEGATIVE_SETTLEMENT_HEAD_POLICY_REQUIRED"));
        verify(heads,never()).selectDuePayoutForUpdate(anyLong(),any());
        verify(heads,never()).markPaidIfPending(anyLong(),any()); verifyNoInteractions(members,ledger,debtRows);
    }

    @Test void paidStatusWithoutOriginalCreditCannotChargeWalletOrCreateDebt() {
        initiator.setStatus(1); when(members.selectBalanceForUpdate(7L)).thenReturn(money("2"));
        assertEquals("ORIGINAL_PAYOUT_CREDIT_RECEIPT_MISSING",assertThrows(IllegalStateException.class,
                ()->owner.reverseCompletedRegistrationRefund(901L)).getMessage());
        verify(members,never()).deductBalance(anyLong(),any()); verifyNoInteractions(ledger,debtRows);
        // Mapper doubles do not roll back the earlier receipt: actual atomic rollback needs the real MySQL slice.
    }

    @Test void freezeSingleOrderUsesActualOriginalPaymentReferenceWithoutChoosingAllocation() {
        when(evidence.recordClose(close)).thenReturn(close);
        when(originals.bindHeadToClose(anyLong(),eq(1L),eq(11L),anyString())).thenReturn(1);
        when(originals.insertComponent(any())).thenReturn(1);
        owner.freezeOriginals(close,List.of(source),components);
        verify(verified,times(2)).requireVerified(paymentReference);
        verify(originals,times(2)).insertComponent(any());
        assertEquals(64,components.get(0).getSourceFingerprint().length());
        assertEquals(money("1"),platform.getAmount()); assertEquals(money("9"),initiator.getAmount());
        verifyNoInteractions(members,ledger,debtRows);
    }

    @Test void existingOriginalSetWithChangedAmountCannotBeOverwrittenOnReplay() {
        when(evidence.recordClose(close)).thenReturn(close);
        CoopSettlementOriginalComponent changed=component(101L,platform,"ACCOUNTING"); changed.setOriginalAmount(money("2"));
        when(originals.selectComponentsForCloseForUpdate(1L)).thenReturn(List.of(changed,components.get(1)));
        assertEquals("ORIGINAL_COMPONENT_PAYLOAD_CONFLICT",assertThrows(IllegalStateException.class,
                ()->owner.freezeOriginals(close,List.of(source),components)).getMessage());
        verify(originals,never()).bindHeadToClose(anyLong(),anyLong(),anyLong(),anyString());
        verify(originals,never()).insertComponent(any()); verifyNoInteractions(members,ledger,debtRows);
    }

    @Test void originalOwnerSelectionIsReadOnlyAndMissingOriginalsCannotFallBackToLegacyClawback() {
        assertTrue(owner.hasFrozenOriginalRefundComponents(901L));
        when(originals.selectComponentsForPayerForUpdate(1L,1L)).thenReturn(List.of());
        assertEquals("ORIGINAL_COMPONENTS_MISSING",assertThrows(IllegalStateException.class,
                ()->owner.hasFrozenOriginalRefundComponents(901L)).getMessage());
        verify(originals,never()).insertReceipt(any()); verify(originals,never()).insertAdjustment(any());
        verifyNoInteractions(members,ledger,debtRows,verified);
    }

    @Test void legacyWithoutOriginalCloseRemainsLegacyOwnedEvenWhenTopicWasSettled() {
        when(evidence.selectCloseForUpdate(11L)).thenReturn(null);
        when(evidence.selectContributionForUpdate(1L)).thenReturn(null);
        assertFalse(owner.hasFrozenOriginalRefundComponents(901L));
        assertTrue(owner.applySucceededRefund(11L,901L).isEmpty());
        verify(originals,never()).insertReceipt(any()); verify(originals,never()).insertAdjustment(any());
        verifyNoInteractions(members,ledger,debtRows,verified);
    }

    @Test void approvedLegacyRefundBlocksOriginalFreezeBeforeAnyCashEvidenceOrComponentWrite() {
        when(originals.selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(11L)).thenReturn(List.of(901L));
        IllegalStateException error=assertThrows(IllegalStateException.class,()->owner.freezeOriginals(close,List.of(source),components));
        assertTrue(error.getMessage().contains("APPROVED_REFUND_PRECEDES_ORIGINAL_CLOSE"));
        verify(evidence,never()).recordClose(any());verify(evidence,never()).recordContribution(any());
        verify(originals,never()).bindHeadToClose(anyLong(),anyLong(),anyLong(),anyString());verify(originals,never()).insertComponent(any());
        verifyNoInteractions(members,ledger,debtRows,verified);
    }
    @Test void unavailableApprovedRefundCurrentReadCannotBeTreatedAsAnEmptyLegacyHistory() {
        when(originals.selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(11L)).thenReturn(null);
        assertTrue(assertThrows(IllegalStateException.class,()->owner.freezeOriginals(close,List.of(source),components))
                .getMessage().contains("APPROVED_REFUND_PRECEDES_ORIGINAL_CLOSE"));
        verify(evidence,never()).recordClose(any());verify(originals,never()).insertComponent(any());
        verifyNoInteractions(members,ledger,debtRows,verified);
    }

    private CoopSettlementOriginalComponent component(Long id,CoopSettlement head,String kind) {
        CoopSettlementOriginalComponent row=new CoopSettlementOriginalComponent(); row.setId(id); row.setCashCloseId(1L); row.setTopicId(11L);
        row.setSourceRegistrationId(1L); row.setPayerRegistrationId(1L); row.setSettlementId(head.getId()); row.setReceiverKind(kind);
        row.setReceiverMemberId(head.getMerchantId()); row.setLineType(head.getLineType()); row.setOriginalAmount(head.getAmount());
        row.setOriginalOrderCash(money("10")); row.setCalculationVersion(close.getOriginCalculationVersion());
        row.setPaymentFactId(paymentReference.id()); row.setPaymentFactHash(paymentReference.hash());
        row.setSourceFingerprint(ReflectionTestUtils.invokeMethod(owner,"fingerprint",row)); return row;
    }
    private static CoopSettlement head(Long id,String line,Long receiver,String amount,int status) {
        CoopSettlement row=new CoopSettlement();row.setId(id);row.setTopicId(11L);row.setLineType(line);row.setMerchantId(receiver);row.setAmount(money(amount));row.setStatus(status);return row;
    }
    private static WechatVerifiedPaymentFact payment() { return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
            "merchant","wx-original","REG1","WXP1","WXP1","WXTX1","WXTX1","JSAPI","CNY",1000,1000,Instant.ofEpochSecond(1),null,"SUCCESS"); }
    private static WechatVerifiedPaymentFact refundFact(String appid) { return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,
            "merchant",appid,"REG1","WXP1","RF901","WXREF1","WXTX1","JSAPI","CNY",1000,1000,Instant.ofEpochSecond(2),null,"REFUND"); }
    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
}
