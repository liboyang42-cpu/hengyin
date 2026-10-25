package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class Wp04TopicSettlementEntryGuardsTest {
    @Test void missingClaimServiceCannotRunLegacyCashFallback() {
        Fixture f = new Fixture(); ReflectionTestUtils.setField(f.service,"coopSettlementClaimService",null);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> f.service.settleCoopTopic(f.topic,new BigDecimal("10")));
        assertTrue(failure.getMessage().contains("COOP_CLAIM_SERVICE_REQUIRED"));
        verifyNoInteractions(f.pricing,f.registrations,f.claims,f.points);
        verify(f.heads,never()).insertCoopSettlement(any());
    }
    @Test void nullPlanCannotUseLiveShareRateOrTopicWideEvidence() {
        Fixture f = new Fixture();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> f.service.settleCoopTopic(f.topic,new BigDecimal("10")));
        assertTrue(failure.getMessage().contains("COOP_CLAIM_PLAN_IDENTITY_CONFLICT"));
        verify(f.registrations,never()).selectVerifiedSalesByTopicAndMerchant(any(),any());
        verify(f.heads,never()).insertCoopSettlement(any()); verify(f.claims,never()).persist(any(),any());
    }
    @Test void paidActivityOnlyCannotCloseTopicAsZeroOrRefundDeposit() {
        Fixture f = new Fixture(); when(f.topics.claimTopicForSettlement(11L)).thenReturn(1);
        when(f.registrations.selectTotalSalesByTopicId(11L)).thenReturn(BigDecimal.ZERO);
        CmsRegistration activity = new CmsRegistration(); activity.setId(51L); activity.setOwnerType(2L);
        activity.setOwnerId(201L); activity.setTopicId(11L); activity.setPaymentStatus(2); activity.setVerificationStatus(0);
        when(f.evidence.selectTopicActivityCashRowsForUpdate(11L)).thenReturn(List.of(activity));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(f.service,"settleTopic",f.topic,new SettlementRule()));
        assertTrue(failure.getMessage().contains("ACTIVITY_CASH_BASIS_UNSUPPORTED"));
        verifyNoInteractions(f.claims,f.points,f.inviteService);
        verify(f.heads,never()).insertCoopSettlement(any());
        // CAS 事务回滚须由真实 MySQL 集成测试确认，此单测不冒充回滚证据。
    }
    @Test void noCoopOrderDoesNotMakePaidActivityFundsDisappearFromZeroCloseGuard() {
        Fixture f = new Fixture(); when(f.orders.selectEffectiveOrdersByTopicId(11L)).thenReturn(List.of());
        when(f.topics.claimTopicForSettlement(11L)).thenReturn(1);
        when(f.registrations.selectTotalSalesByTopicId(11L)).thenReturn(BigDecimal.ZERO);
        CmsRegistration activity = new CmsRegistration(); activity.setId(51L); activity.setOwnerType(2L);
        activity.setOwnerId(201L); activity.setTopicId(11L); activity.setPaymentStatus(2); activity.setVerificationStatus(0);
        when(f.evidence.selectTopicActivityCashRowsForUpdate(11L)).thenReturn(List.of(activity));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(f.service,"settleTopic",f.topic,new SettlementRule()));
        assertTrue(failure.getMessage().contains("ACTIVITY_CASH_BASIS_UNSUPPORTED"));
        verifyNoInteractions(f.claims,f.points,f.inviteService);
    }
    @Test void mismatchedInitiatorSnapshotIsRejectedBeforePayableWrites() {
        Fixture f = new Fixture(); CoopSettlementPlan plan = f.plan(); plan.getSnapshot().setInitiatorMemberId(99L);
        when(f.claims.prepare(any(),any())).thenReturn(plan);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> f.service.settleCoopTopic(f.topic,new BigDecimal("10")));
        assertTrue(failure.getMessage().contains("TOPIC_INITIATOR_SNAPSHOT_CONFLICT"));
        verify(f.heads,never()).insertCoopSettlement(any()); verify(f.claims,never()).persist(any(),any());
    }
    @Test void nonzeroPlanComposesConservedPayablesWithoutChangingPercentages() {
        Fixture f = new Fixture(); CoopSettlementPlan plan = f.plan(); when(f.claims.prepare(any(),any())).thenReturn(plan);
        f.service.settleCoopTopic(f.topic,new BigDecimal("10.00"));
        ArgumentCaptor<CoopSettlement> ordinary = ArgumentCaptor.forClass(CoopSettlement.class);
        verify(f.heads,times(3)).insertCoopSettlement(ordinary.capture());
        ArgumentCaptor<CoopSettlement> merchant = ArgumentCaptor.forClass(CoopSettlement.class);
        verify(f.claims).persist(eq(plan),merchant.capture());
        BigDecimal sum = ordinary.getAllValues().stream().map(CoopSettlement::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add)
                .add(merchant.getValue().getAmount());
        assertEquals(0,new BigDecimal("10.00").compareTo(sum));
        assertEquals(new BigDecimal("2.00"),merchant.getValue().getAmount());
        assertEquals(88L,merchant.getValue().getMerchantId());
        verify(f.pricing).initiatorGuaranteeRateOf(f.topic,1);
        verify(f.inviteService).requestTopicDepositRefunds(11L);
    }
    @Test void staleSalesArgumentCannotRewriteCurrentLockedCashBasis() {
        Fixture f = new Fixture(); CoopSettlementPlan plan = f.plan(); when(f.claims.prepare(any(),any())).thenReturn(plan);
        // 模拟事务先前 RR 读到 999，退款/状态变更后当前锁读只剩 10；金额仍须守恒于 10。
        f.service.settleCoopTopic(f.topic, new BigDecimal("999.00"));
        ArgumentCaptor<CoopSettlement> ordinary = ArgumentCaptor.forClass(CoopSettlement.class);
        verify(f.heads,times(3)).insertCoopSettlement(ordinary.capture());
        ArgumentCaptor<CoopSettlement> merchant = ArgumentCaptor.forClass(CoopSettlement.class);
        verify(f.claims).persist(eq(plan),merchant.capture());
        BigDecimal sum = ordinary.getAllValues().stream().map(CoopSettlement::getAmount)
                .reduce(BigDecimal.ZERO,BigDecimal::add).add(merchant.getValue().getAmount());
        assertEquals(0,new BigDecimal("10.00").compareTo(sum));
        verify(f.evidence).selectTopicCashRowsForUpdate(11L);
        verify(f.registrations,never()).selectVerifiedSalesByTopicId(any());
        verify(f.registrations,never()).selectVerifiedHeadsByTopicId(any());
        verify(f.registrations,never()).selectVerifiedRegistrationIdsByTopicId(any());
    }
    @Test void zeroCashWithCoopCannotCloseOrRefundDepositsWithoutFrozenContractValidation() {
        Fixture f = new Fixture(); when(f.topics.claimTopicForSettlement(11L)).thenReturn(1);
        when(f.evidence.selectTopicCashRowsForUpdate(11L)).thenReturn(List.of());
        when(f.claims.prepare(any(),any())).thenThrow(new IllegalStateException("MANUAL_REPAIR:MISSING_FROZEN_TERMS"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(f.service,"settleTopic",f.topic,new SettlementRule()));
        assertTrue(failure.getMessage().contains("MISSING_FROZEN_TERMS"));
        verify(f.claims).prepare(any(),any());
        verifyNoInteractions(f.inviteService,f.points);
        verify(f.heads,never()).insertCoopSettlement(any());
    }
    @Test void zeroCashCannotConsumePositivePreparedObligation() {
        Fixture f = new Fixture(); when(f.topics.claimTopicForSettlement(11L)).thenReturn(1);
        when(f.evidence.selectTopicCashRowsForUpdate(11L)).thenReturn(List.of());
        when(f.claims.prepare(any(),any())).thenReturn(f.plan());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(f.service,"settleTopic",f.topic,new SettlementRule()));
        assertTrue(failure.getMessage().contains("ZERO_CASH_COMPENSATION_UNPROVEN"));
        verifyNoInteractions(f.inviteService,f.points);
        verify(f.claims,never()).persist(any(),any()); verify(f.heads,never()).insertCoopSettlement(any());
    }
    @Test void provenNoneZeroContractPreservesIdentityBeforeDepositRefund() {
        Fixture f = new Fixture(); when(f.topics.claimTopicForSettlement(11L)).thenReturn(1);
        when(f.evidence.selectTopicCashRowsForUpdate(11L)).thenReturn(List.of());
        CoopSettlementSnapshot snapshot = f.plan().getSnapshot(); snapshot.setCompensationMode("NONE");
        CoopSettlementPlan none = new CoopSettlementPlan(snapshot,"NONE",List.of());
        when(f.claims.prepare(any(),any())).thenReturn(none);
        assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(f.service,"settleTopic",f.topic,new SettlementRule()));
        org.mockito.InOrder sequence = inOrder(f.claims,f.inviteService);
        sequence.verify(f.claims).prepare(any(),any()); sequence.verify(f.claims).persist(none,null);
        sequence.verify(f.inviteService).requestTopicDepositRefunds(11L);
        verifyNoInteractions(f.points); verify(f.heads,never()).insertCoopSettlement(any());
    }
    @Test void zeroCashStillRejectsPendingOrApprovedRefundBasis() {
        Fixture f = new Fixture(); when(f.topics.claimTopicForSettlement(11L)).thenReturn(1);
        when(f.evidence.selectTopicCashRowsForUpdate(11L)).thenReturn(List.of());
        when(f.evidence.selectCashBasisBlockingRefundIdsForUpdate(11L)).thenReturn(List.of(91L));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(f.service,"settleTopic",f.topic,new SettlementRule()));
        assertTrue(failure.getMessage().contains("REFUND_NET_BASIS_UNPROVEN"));
        verifyNoInteractions(f.claims,f.points,f.inviteService); verify(f.heads,never()).insertCoopSettlement(any());
    }
    @Test void trulyEmptyTopicWithoutContractCanStillCloseOnce() {
        Fixture f = new Fixture(); when(f.topics.claimTopicForSettlement(11L)).thenReturn(1);
        when(f.evidence.selectTopicCashRowsForUpdate(11L)).thenReturn(List.of());
        when(f.orders.selectEffectiveOrdersByTopicId(11L)).thenReturn(List.of());
        assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(f.service,"settleTopic",f.topic,new SettlementRule()));
        verify(f.inviteService).requestTopicDepositRefunds(11L);
        verifyNoInteractions(f.claims,f.points); verify(f.heads,never()).insertCoopSettlement(any());
    }
    @Test void approvedRefundHistoryCannotBeHiddenByAnEmptyCashBasis() {
        Fixture f = new Fixture(); when(f.topics.claimTopicForSettlement(11L)).thenReturn(1);
        when(f.evidence.selectTopicCashRowsForUpdate(11L)).thenReturn(List.of());
        CoopSettlementOriginalMapper original = OriginalCloseRefundHistoryFixture.install(f.service,11L,List.of(91L));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(f.service,"settleTopic",f.topic,new SettlementRule()));
        assertTrue(failure.getMessage().contains("APPROVED_REFUND_PRECEDES_ORIGINAL_CLOSE"));
        verify(original).selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(11L);
        verifyNoInteractions(f.claims,f.points,f.inviteService); verify(f.heads,never()).insertCoopSettlement(any());
    }
    @Test void unavailableOriginalRefundReadCannotBecomeAnEmptyHistory() {
        Fixture f = new Fixture(); when(f.topics.claimTopicForSettlement(11L)).thenReturn(1);
        when(f.evidence.selectTopicCashRowsForUpdate(11L)).thenReturn(List.of());
        CoopSettlementOriginalMapper original = OriginalCloseRefundHistoryFixture.install(f.service,11L,null);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(f.service,"settleTopic",f.topic,new SettlementRule()));
        assertTrue(failure.getMessage().contains("APPROVED_REFUND_PRECEDES_ORIGINAL_CLOSE"));
        verify(original).selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(11L);
        verifyNoInteractions(f.claims,f.points,f.inviteService); verify(f.heads,never()).insertCoopSettlement(any());
    }
    private static final class Fixture {
        final CmsTopicServiceImpl service = new CmsTopicServiceImpl(); final CmsTopic topic = new CmsTopic();
        final CoopSettlementMapper heads = mock(CoopSettlementMapper.class);
        final CoopOrderMapper orders = mock(CoopOrderMapper.class);
        final CmsTopicMapper topics = mock(CmsTopicMapper.class);
        final CoopSettlementEvidenceMapper evidence = mock(CoopSettlementEvidenceMapper.class);
        final CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        final ICoopSettlementClaimService claims = mock(ICoopSettlementClaimService.class);
        final IPricingService pricing = mock(IPricingService.class);
        final IPointsAwardService points = mock(IPointsAwardService.class);
        final ICoopInviteService inviteService = mock(ICoopInviteService.class);
        Fixture() {
            CmsRegistration cash = new CmsRegistration(); cash.setId(31L); cash.setOwnerType(1L); cash.setOwnerId(11L);
            cash.setMemberId(44L); cash.setPaymentStatus(2); cash.setRegistrationStatus(2); cash.setVerificationStatus(1);
            cash.setPayableAmount(new BigDecimal("10.00"));
            when(evidence.selectTopicCashRowsForUpdate(11L)).thenReturn(List.of(cash));
            topic.setId(11L); topic.setMemberId(77L); topic.setProductType(1);
            when(topics.selectCmsTopicByIdForUpdate(11L)).thenReturn(topic);
            CoopOrder order = new CoopOrder(); order.setId(701L); order.setTopicId(11L); order.setToType("merchant"); order.setToId(88L);
            order.setShareMode(1); order.setShareRate(new BigDecimal("99")); // live 值不能代替 prepare 输出。
            when(orders.selectEffectiveOrdersByTopicId(11L)).thenReturn(List.of(order));
            when(pricing.platformRateOf(topic)).thenReturn(new BigDecimal("0.10"));
            when(pricing.initiatorGuaranteeRateOf(topic,1)).thenReturn(new BigDecimal("0.07"));
            when(heads.insertCoopSettlement(any())).thenReturn(1);
            ReflectionTestUtils.setField(service,"coopSettlementMapper",heads); ReflectionTestUtils.setField(service,"coopOrderMapper",orders);
            ReflectionTestUtils.setField(service,"cmsTopicMapper",topics); ReflectionTestUtils.setField(service,"coopSettlementEvidenceMapper",evidence);
            OriginalCloseRefundHistoryFixture.install(service,11L,List.of());
            ReflectionTestUtils.setField(service,"cmsRegistrationMapper",registrations); ReflectionTestUtils.setField(service,"coopSettlementClaimService",claims);
            ReflectionTestUtils.setField(service,"pricingService",pricing); ReflectionTestUtils.setField(service,"pointsAwardService",points);
            ReflectionTestUtils.setField(service,"coopInviteMapper",mock(CoopInviteMapper.class)); ReflectionTestUtils.setField(service,"coopInviteService",inviteService);
        }
        CoopSettlementPlan plan() {
            CoopSettlementSnapshot s = new CoopSettlementSnapshot(); s.setOrderId(701L); s.setTopicId(11L); s.setInitiatorMemberId(77L);
            s.setBeneficiaryRole("MERCHANT"); s.setBeneficiaryId(88L); s.setServiceUnit("MERCHANT_PARTICIPATION");
            s.setCommercialContextType("TOPIC_EDITION"); s.setCommercialContextId(11L);
            s.setScopeType("TOPIC"); s.setScopeId(11L);
            s.setCompensationMode("FIXED_PER_FULFILLMENT"); s.setFixedFee(new BigDecimal("2.00"));
            CoopSettlementOriginLine line = new CoopSettlementOriginLine(31L,new BigDecimal("2.00"));
            line.setSettlementRoute("COOP_ORDER"); line.setSourceContractType("COOP_ORDER"); line.setSourceContractId(701L);
            line.setCommercialContextType(s.getCommercialContextType()); line.setCommercialContextId(s.getCommercialContextId());
            line.setBeneficiaryRole(s.getBeneficiaryRole()); line.setBeneficiaryId(s.getBeneficiaryId()); line.setServiceUnit(s.getServiceUnit());
            line.setEligibleSalesSnapshot(new BigDecimal("10.00")); return new CoopSettlementPlan(s,"COOP_ORDER",List.of(line));
        }
    }
}
