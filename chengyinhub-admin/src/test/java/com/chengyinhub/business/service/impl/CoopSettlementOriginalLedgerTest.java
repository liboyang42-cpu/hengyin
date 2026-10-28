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
    final List<CoopSettlementRefundReceipt> storedReceipts = new ArrayList<>();
    BigDecimal frozenCash = money("10");
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
        when(originals.selectReceiptsForPayerForUpdate(1L,1L)).thenAnswer(call -> new ArrayList<>(storedReceipts));
        when(originals.selectReceiptForUpdate(anyLong())).thenAnswer(call -> storedReceipts.stream()
                .filter(row -> row.getRefundId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(originals.insertReceipt(any())).thenAnswer(call -> { receipt[0]=call.getArgument(0); receipt[0].setId(1001L+storedReceipts.size()); storedReceipts.add(receipt[0]); return 1; });
        when(originals.insertAdjustment(any())).thenAnswer(call -> { CoopSettlementOriginalAdjustment row=call.getArgument(0); row.setId(2001L+applied.size()); applied.add(row); return 1; });
        when(originals.selectAdjustmentsForReceiptForUpdate(anyLong())).thenAnswer(call -> applied.stream()
                .filter(row -> row.getReceiptId().equals(call.getArgument(0))).toList());
        when(originals.selectAdjustmentsForSettlementForUpdate(anyLong())).thenAnswer(call -> applied.stream()
                .filter(row -> row.getSettlementId().equals(call.getArgument(0))).toList());
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

    @Test void oneCentSuccessAlwaysReversesOneCentAndFinalRefundClearsEveryFrozenCent() {
        frozenOrder("0.03","0.01","0.01","0.01");
        successfulRefund(901L,"0.01"); owner.reverseCompletedRegistrationRefund(901L);
        assertEquals(1,applied.size()); assertEquals(money("-0.01"),applied.get(0).getAmount());
        assertEquals(101L,applied.get(0).getComponentId(),"equal remainders use original stable identity");
        successfulRefund(902L,"0.01"); owner.reverseCompletedRegistrationRefund(902L);
        successfulRefund(903L,"0.01"); owner.reverseCompletedRegistrationRefund(903L);
        for (var row : storedReceipts) assertEquals(money("0.01"),row.getReversedAmount());
        for (var component : components) assertEquals(component.getOriginalAmount(),reversed(component.getId()));
        int writes=applied.size();
        assertEquals(1002L,owner.applySucceededRefund(11L,902L).orElseThrow().getId());
        assertEquals(writes,applied.size()); assertEquals(3,storedReceipts.size());
        verifyNoInteractions(members,ledger,debtRows);
    }

    @Test void cumulativeFourThenFiveCentsCannotRevokeTheThirdPartsEarlierRemainder() {
        frozenOrder("0.09","0.05","0.03","0.01");
        successfulRefund(901L,"0.04"); owner.reverseCompletedRegistrationRefund(901L);
        assertEquals(money("0.02"),reversed(101L)); assertEquals(money("0.01"),reversed(102L));
        assertEquals(money("0.01"),reversed(103L));
        int start=applied.size();
        Collections.reverse(components); // Mapper row order is not the economic tie breaker.
        successfulRefund(902L,"0.01"); owner.reverseCompletedRegistrationRefund(902L);
        assertEquals(start+1,applied.size()); assertEquals(money("-0.01"),applied.get(start).getAmount());
        assertEquals(101L,applied.get(start).getComponentId());
        assertEquals(money("0.03"),reversed(101L)); assertEquals(money("0.01"),reversed(102L));
        assertEquals(money("0.01"),reversed(103L),"independent 3/2/0 rounding must not withdraw the prior third cent");
        successfulRefund(903L,"0.04"); owner.reverseCompletedRegistrationRefund(903L);
        for (var component : components) assertEquals(component.getOriginalAmount(),reversed(component.getId()));
        assertEquals(money("0.09"),storedReceipts.stream().map(CoopSettlementRefundReceipt::getReversedAmount).reduce(money("0"),BigDecimal::add));
    }

    @Test void cumulativeTinySplitAndOutOfOrderRefundIdsKeepOriginalBoundsAndReplayReceipts() {
        frozenOrder("10.00","1.00","9.00");
        long[] ids={906,901,904,902,905,903};
        String[] amounts={"0.01","0.01","0.03","0.11","1.84","8.00"};
        BigDecimal cumulative=money("0");
        for (int i=0;i<ids.length;i++) {
            Map<Long,BigDecimal> before=new HashMap<>();
            for(var component:components) before.put(component.getId(),reversed(component.getId()));
            successfulRefund(ids[i],amounts[i]); owner.reverseCompletedRegistrationRefund(ids[i]);
            assertEquals(cumulative,receipt[0].getCumulativeBefore()); cumulative=cumulative.add(money(amounts[i]));
            assertEquals(cumulative,receipt[0].getCumulativeAfter()); assertEquals(money(amounts[i]),receipt[0].getReversedAmount());
            for(var component:components) {
                BigDecimal actual=reversed(component.getId());
                assertTrue(actual.compareTo(before.get(component.getId()))>=0);
                assertTrue(actual.compareTo(component.getOriginalAmount())<=0);
            }
        }
        int effects=applied.size();
        for(long id:new long[]{904,906,901,903}) owner.reverseCompletedRegistrationRefund(id);
        assertEquals(effects,applied.size()); assertEquals(ids.length,storedReceipts.size());
        assertEquals(money("1"),platform.getAmount()); assertEquals(money("9"),initiator.getAmount());
        for(var component:components) assertEquals(component.getOriginalAmount(),reversed(component.getId()));
    }

    @Test void channelUpperBoundaryAndFinalRemaindersUseExactIntegerMoney() {
        frozenOrder("21474836.47","10000000.00","11474836.47");
        successfulRefund(901L,"0.01"); owner.reverseCompletedRegistrationRefund(901L);
        assertEquals(money("0.01"),receipt[0].getReversedAmount());
        successfulRefund(902L,"21474836.46"); owner.reverseCompletedRegistrationRefund(902L);
        for(var component:components) assertEquals(component.getOriginalAmount(),reversed(component.getId()));
        assertEquals(money("21474836.47"),receipt[0].getCumulativeAfter());
    }

    @Test void approvedButNotArrivedAndOverRefundCannotConsumeAnyRemainingBudget() {
        refund.setPayoutStatus(RefundApplication.PAYOUT_PROCESSING);
        assertEquals("REFUND_SUCCESS_CAS_REQUIRED",assertThrows(IllegalStateException.class,
                ()->owner.reverseCompletedRegistrationRefund(901L)).getMessage());
        assertTrue(storedReceipts.isEmpty()); assertTrue(applied.isEmpty());
        successfulRefund(901L,"10.00"); owner.reverseCompletedRegistrationRefund(901L);
        int effects=applied.size();
        successfulRefund(902L,"0.01");
        assertEquals("ORIGINAL_ORDER_OVER_REFUND",assertThrows(IllegalStateException.class,
                ()->owner.reverseCompletedRegistrationRefund(902L)).getMessage());
        assertEquals(effects,applied.size()); assertEquals(1,storedReceipts.size());
    }

    @Test void missingOriginalValueAndCorruptHistoricalEffectsNeverCreateAFallbackComponent() {
        components.remove(0);
        assertEquals("ORIGINAL_ORDER_CONSERVATION",assertThrows(IllegalStateException.class,
                ()->owner.reverseCompletedRegistrationRefund(901L)).getMessage());
        assertTrue(applied.isEmpty()); assertTrue(storedReceipts.isEmpty());
        components.add(component(101L,platform,"ACCOUNTING"));
        successfulRefund(901L,"0.01"); owner.reverseCompletedRegistrationRefund(901L);
        CoopSettlementOriginalAdjustment first=applied.get(0); applied.add(first);
        successfulRefund(902L,"0.01");
        assertEquals("REFUND_RECEIPT_COMPONENT_CONFLICT",assertThrows(IllegalStateException.class,
                ()->owner.reverseCompletedRegistrationRefund(902L)).getMessage());
        assertEquals(1,storedReceipts.size()); assertEquals(2,applied.size(),"corrupt input retained, no new economic effect");
        verifyNoInteractions(members,ledger,debtRows);
    }

    @Test void changedSuccessIdentityCannotReuseAnExistingRefundReceiptOrAdjustmentKey() {
        successfulRefund(901L,"1.00"); owner.reverseCompletedRegistrationRefund(901L);
        int effects=applied.size();
        successfulRefund(901L,"2.00");
        assertThrows(IllegalStateException.class,()->owner.reverseCompletedRegistrationRefund(901L));
        assertEquals(1,storedReceipts.size()); assertEquals(effects,applied.size());
    }

    private BigDecimal reversed(Long componentId) {
        return applied.stream().filter(row -> componentId.equals(row.getComponentId()))
                .map(row -> row.getAmount().negate()).reduce(money("0"),BigDecimal::add);
    }

    private void frozenOrder(String total,String... amounts) {
        frozenCash=money(total); close.setOriginalSalesBasis(frozenCash); source.setSalesBasisAmount(frozenCash);
        long fen=frozenCash.movePointRight(2).longValueExact();
        payment=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"merchant","wx-original",
                "REG1","WXP1","WXP1","WXTX1","WXTX1","JSAPI","CNY",fen,fen,Instant.ofEpochSecond(1),null,"SUCCESS");
        paymentReference=new WechatVerifiedPaymentFact.Reference(31L,payment.factHash());
        when(verified.requireVerified(paymentReference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(paymentReference,payment));
        components.clear();
        for(int i=0;i<amounts.length;i++) {
            CoopSettlement head=i==0?platform:i==1?initiator:head(11L+i,"INITIATOR_GUARANTEE",7L+i,amounts[i],0);
            head.setAmount(money(amounts[i]));
            when(heads.selectByIdForUpdate(head.getId())).thenReturn(head);
            CoopSettlementOriginalComponent component=component(101L+i,head,i==0?"ACCOUNTING":"MEMBER");
            component.setOriginalOrderCash(frozenCash);
            component.setSourceFingerprint(ReflectionTestUtils.invokeMethod(owner,"fingerprint",component));components.add(component);
        }
    }

    private void successfulRefund(Long id,String amount) {
        long fen=money(amount).movePointRight(2).longValueExact();
        var fact=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,"merchant","wx-original",
                "REG1","WXP1","RF"+id,"WXREF"+id,"WXTX1","JSAPI","CNY",
                frozenCash.movePointRight(2).longValueExact(),fen,Instant.ofEpochSecond(id),null,"REFUND");
        var reference=new WechatVerifiedPaymentFact.Reference(90000L+id,fact.factHash());
        when(verified.requireVerified(reference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(reference,fact));
        when(originals.selectRefundVerifiedReferenceForUpdate(id)).thenReturn(reference);
        RefundApplication current=new RefundApplication();current.setId(id);current.setRefundNo("RF"+id);
        current.setSourceType("registration");current.setSourceId(1L);current.setStatus(1);
        current.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);current.setRefundAmount(money(amount));
        when(originals.selectRefundForUpdate(id)).thenReturn(current);
        CoopOriginalRefundScope currentScope=new CoopOriginalRefundScope();currentScope.setRefundId(id);
        currentScope.setSourceType("registration");currentScope.setSourceId(1L);currentScope.setRegistrationId(1L);
        currentScope.setOwnerType(1L);currentScope.setOwnerId(11L);currentScope.setTopicId(11L);currentScope.setRelationPresent(true);
        when(originals.selectRefundScope(id)).thenReturn(currentScope);when(originals.selectRefundScopeForUpdate(id)).thenReturn(currentScope);
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
