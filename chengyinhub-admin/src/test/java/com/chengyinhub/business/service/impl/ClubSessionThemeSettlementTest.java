package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 第十二轮 k5:俱乐部主题按场次结算时,主题级结算不误报、不拒结、不二次付钱。
 * 旧代码:在世俱乐部场次的已付票进主题现金基数 ⇒ ACTIVITY_CASH_BASIS_UNSUPPORTED;
 * 主题级没有现金时按核销付费的单 ⇒ NO_PROVEN_FULFILLMENT / ZERO_CASH_COMPENSATION_UNPROVEN,每轮告警。
 */
class ClubSessionThemeSettlementTest {
    private static final long TOPIC = 11L, SESSION_A = 201L, SESSION_B = 202L;

    @Test
    void clubThemeWithOnlySessionCashClosesWithoutTopicPlanOrPayment() {
        Fixture f = new Fixture();
        when(f.evidence.selectTopicCashRowsForUpdate(TOPIC)).thenReturn(List.of());
        when(f.evidence.selectTopicActivityCashRowsForUpdate(TOPIC)).thenReturn(List.of(f.sessionRow(51L, SESSION_A), f.sessionRow(52L, SESSION_B)));
        when(f.evidence.selectTopicNonClubSessionCashRowsForUpdate(TOPIC)).thenReturn(List.of());
        when(f.evidence.selectLiveClubSessionIdsByTopicId(TOPIC)).thenReturn(List.of(SESSION_A, SESSION_B));

        Object settled = ReflectionTestUtils.invokeMethod(f.service, "settleTopic", f.topic, new SettlementRule());

        assertEquals(Boolean.TRUE, settled);
        verify(f.claims, never()).prepare(any(), any());
        verify(f.claims, never()).persist(any(), any());
        verify(f.heads, never()).insertCoopSettlement(any());
        verify(f.inviteService).requestTopicDepositRefunds(TOPIC);
    }

    @Test
    void sessionSettledPlanIsSkippedWhileThemeCashStillConserves() {
        Fixture f = new Fixture();
        CmsRegistration cash = new CmsRegistration(); cash.setId(31L); cash.setOwnerType(1L); cash.setOwnerId(TOPIC);
        cash.setMemberId(44L); cash.setPaymentStatus(2); cash.setRegistrationStatus(2); cash.setVerificationStatus(0);
        cash.setPayableAmount(new BigDecimal("10.00"));
        when(f.evidence.selectTopicCashRowsForUpdate(TOPIC)).thenReturn(List.of(cash));
        when(f.evidence.selectTopicActivityCashRowsForUpdate(TOPIC)).thenReturn(List.of(f.sessionRow(51L, SESSION_A)));
        when(f.evidence.selectTopicNonClubSessionCashRowsForUpdate(TOPIC)).thenReturn(List.of());
        CoopSettlementSnapshot s = new CoopSettlementSnapshot(); s.setOrderId(701L); s.setTopicId(TOPIC); s.setInitiatorMemberId(77L);
        when(f.claims.prepare(any(), any())).thenReturn(new CoopSettlementPlan(s, ICoopSettlementClaimService.ROUTE_SETTLED_PER_SESSION, List.of()));
        when(f.pricing.initiatorGuaranteeRateOf(f.topic, 0)).thenReturn(new BigDecimal("0.05"));

        f.service.settleCoopTopic(f.topic, new BigDecimal("999"));

        verify(f.claims, never()).persist(any(), any());
        ArgumentCaptor<CoopSettlement> lines = ArgumentCaptor.forClass(CoopSettlement.class);
        verify(f.heads, atLeastOnce()).insertCoopSettlement(lines.capture());
        BigDecimal sum = lines.getAllValues().stream().map(CoopSettlement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("10.00").compareTo(sum), "主题级只按主题票 10.00 分,场次票不在里面");
        assertTrue(lines.getAllValues().stream().noneMatch(l -> l.getCoopOrderId() != null), "按场次结算的单没有主题级商家行");
    }

    @Test
    void prepareCandidatesDropLiveClubSessionRegistrations() {
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        CoopSettlementEvidenceMapper evidence = mock(CoopSettlementEvidenceMapper.class);
        TopicSettlementEvidenceService service = new TopicSettlementEvidenceService(activities,
                mock(CmsRegistrationMapper.class), mock(CoopGroupVerifyMapper.class), evidence);
        when(activities.selectActiveActivityIdsByTopicId(TOPIC)).thenReturn(List.of(SESSION_A, 300L));
        CoopSettlementCandidate session = candidate(61L, 2L, SESSION_A);
        CoopSettlementCandidate otherActivity = candidate(62L, 2L, 300L);
        CoopSettlementCandidate themeTicket = candidate(63L, 1L, TOPIC);
        when(evidence.selectTopicEvidenceCandidatesForUpdate(any(), any(), any())).thenReturn(List.of(session, otherActivity, themeTicket));
        when(evidence.selectLiveClubSessionIdsByTopicId(TOPIC)).thenReturn(List.of(SESSION_A));
        CoopSettlementSnapshot s = new CoopSettlementSnapshot(); s.setTopicId(TOPIC); s.setCompensationMode("NONE");

        List<CoopSettlementCandidate> kept = service.lockCandidates(s, new Date());

        assertEquals(List.of(otherActivity, themeTicket), kept, "只去掉在世俱乐部场次的报名;别的活动壳照旧交给合同判定");
    }

    private static CoopSettlementCandidate candidate(long id, long ownerType, long ownerId) {
        CoopSettlementCandidate c = new CoopSettlementCandidate();
        c.setRegistrationId(id); c.setOwnerType(ownerType); c.setOwnerId(ownerId);
        return c;
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
        final ICoopInviteService inviteService = mock(ICoopInviteService.class);
        final CoopInviteMapper invites = mock(CoopInviteMapper.class);
        Fixture() {
            topic.setId(TOPIC); topic.setMemberId(77L); topic.setProductType(1); topic.setClubId(990004L);
            when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
            when(topics.claimTopicForSettlement(TOPIC)).thenReturn(1);
            when(registrations.selectTotalSalesByTopicId(TOPIC)).thenReturn(BigDecimal.ZERO);
            CoopOrder order = new CoopOrder(); order.setId(701L); order.setTopicId(TOPIC); order.setToType("merchant"); order.setToId(9002L);
            order.setShareMode(2); order.setFixedFee(new BigDecimal("8.00"));
            when(orders.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(List.of(order));
            when(evidence.selectLiveClubSessionIdsByTopicId(TOPIC)).thenReturn(List.of(SESSION_A));
            when(pricing.platformRateOf(topic)).thenReturn(new BigDecimal("0.03"));
            when(heads.insertCoopSettlement(any())).thenReturn(1);
            when(invites.selectAcceptedByTopicForUpdate(TOPIC)).thenReturn(List.of());
            ReflectionTestUtils.setField(service, "coopSettlementMapper", heads); ReflectionTestUtils.setField(service, "coopOrderMapper", orders);
            ReflectionTestUtils.setField(service, "cmsTopicMapper", topics); ReflectionTestUtils.setField(service, "coopSettlementEvidenceMapper", evidence);
            OriginalCloseRefundHistoryFixture.install(service, TOPIC, List.of());
            ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations); ReflectionTestUtils.setField(service, "coopSettlementClaimService", claims);
            ReflectionTestUtils.setField(service, "pricingService", pricing); ReflectionTestUtils.setField(service, "pointsAwardService", mock(IPointsAwardService.class));
            ReflectionTestUtils.setField(service, "settlementRuleService", mock(ISettlementRuleService.class));
            ReflectionTestUtils.setField(service, "coopInviteMapper", invites); ReflectionTestUtils.setField(service, "coopInviteService", inviteService);
        }
        CmsRegistration sessionRow(long id, long activityId) {
            CmsRegistration r = new CmsRegistration(); r.setId(id); r.setOwnerType(2L); r.setOwnerId(activityId); r.setTopicId(TOPIC);
            r.setMemberId(id); r.setPaymentStatus(2); r.setRegistrationStatus(2); r.setVerificationStatus(1); r.setPayableAmount(new BigDecimal("39.00"));
            return r;
        }
    }
}
