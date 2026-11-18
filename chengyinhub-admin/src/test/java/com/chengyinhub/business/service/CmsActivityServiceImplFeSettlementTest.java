package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.FeReferenceSettlement;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ActivitySettlementHoldMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.support.ClubPrincipalPayee;
import com.chengyinhub.business.service.support.ClubSessionMerchantFee;
import com.chengyinhub.business.service.support.SessionPayeeResolver;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F11(FE-G16=B / FE-G22):自由探索场次的计酬不再线上划款给店铺 —— merchantFeePlan 恒空、
 * 不扣主理人份额、不入店铺余额;原来那笔改写成 REVSHARE_HEAD 参考行。
 * 非自由探索的计酬场次仍线上划款(回归)。
 */
class CmsActivityServiceImplFeSettlementTest {

    private static final Long ACTIVITY = 100L, CLUB = 7002L, PRINCIPAL = 11L, PLATFORM = 8L, SHOP = 21L, TOPIC = 600L;

    private CmsActivityServiceImpl svc;
    private IUmsMemberService members;
    private ClubSessionMerchantFee fees;
    private IFeReferenceSettlementService reference;
    private CmsTopic topic;
    private CmsActivity activity;
    private ClubSessionMerchantFee.Plan plan;

    @BeforeEach
    void setUp() {
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        ClubMapper clubs = mock(ClubMapper.class);
        members = mock(IUmsMemberService.class);
        fees = mock(ClubSessionMerchantFee.class);
        reference = mock(IFeReferenceSettlementService.class);
        CmsSettingMapper settings = mock(CmsSettingMapper.class);
        ISettlementRuleService rules = mock(ISettlementRuleService.class);
        IUmsMemberBalanceDetailService balanceDetails = mock(IUmsMemberBalanceDetailService.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TopicSaleSnapshotMapper snapshots = mock(TopicSaleSnapshotMapper.class);
        when(snapshots.selectSnapshotByTopicId(TOPIC)).thenReturn(TopicSaleCanonicalTestFixture.ordinarySnapshot(TOPIC));

        ClubPrincipalPayee payee = new ClubPrincipalPayee();
        ReflectionTestUtils.setField(payee, "clubMapper", clubs);
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(PRINCIPAL);
        when(clubs.selectClubById(CLUB)).thenReturn(club);

        svc = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(svc, "cmsSettingMapper", settings);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(svc, "settlementEntryService", mock(ISettlementEntryService.class));
        ReflectionTestUtils.setField(svc, "topicSaleSnapshotMapper", snapshots);
        ReflectionTestUtils.setField(svc, "settlementDetailMapper", mock(SettlementDetailMapper.class));
        ReflectionTestUtils.setField(svc, "umsMemberService", members);
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailService", balanceDetails);
        ReflectionTestUtils.setField(svc, "settlementRuleService", rules);
        ReflectionTestUtils.setField(svc, "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(svc, "transactionManager", tm);
        ReflectionTestUtils.setField(svc, "activitySettlementHoldMapper", mock(ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(svc, "settlementDebtLedger", mock(SettlementDebtLedger.class));
        ReflectionTestUtils.setField(svc, "clubPrincipalPayee", payee);
        ReflectionTestUtils.setField(svc, "clubSessionMerchantFee", fees);
        ReflectionTestUtils.setField(svc, "feReferenceSettlementService", reference);

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(2);
        topic.setClubId(CLUB);
        topic.setMemberId(PRINCIPAL);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setName("示例站点 · 场次");
        activity.setMemberId(PRINCIPAL);
        activity.setClubId(CLUB);
        activity.setTopicId(TOPIC);
        when(activities.selectActivitiesForSettlement()).thenReturn(Collections.singletonList(activity));
        when(activities.claimActivityForSettlement(ACTIVITY)).thenReturn(1);

        CmsRegistration paid = new CmsRegistration();
        paid.setId(5001L);
        paid.setPaymentStatus(2);
        paid.setRegistrationStatus(2);
        paid.setPayableAmount(new BigDecimal("100.00"));
        paid.setFeePlatformRate(new BigDecimal("0.03"));
        when(registrations.selectPaidUnrefundedIdsByActivityId(ACTIVITY)).thenReturn(Collections.singletonList(5001L));
        when(registrations.selectCmsRegistrationByIdForUpdate(5001L)).thenReturn(paid);
        when(registrations.selectTotalSalesByActivityId(ACTIVITY)).thenReturn(new BigDecimal("100.00"));

        CmsSetting setting = new CmsSetting();
        setting.setPlatformMemberId(PLATFORM);
        when(settings.selectCmsSettingById(1L)).thenReturn(setting);
        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.03"));
        rule.setMerchantRate(new BigDecimal("0.97"));
        rule.setCreatorRate(BigDecimal.ZERO);
        rule.setInviterRate(BigDecimal.ZERO);
        when(rules.getActiveRule(0L, "activity")).thenReturn(rule);
        when(members.lockMemberById(PLATFORM)).thenReturn(PLATFORM);
        UmsMember usable = new UmsMember();
        usable.setStatus(1L);
        usable.setDelFlag(0);
        usable.setBalance(BigDecimal.ZERO);
        when(members.selectUmsMemberById(anyLong())).thenReturn(usable);
        when(members.addBalance(anyLong(), any())).thenReturn(true);
        when(balanceDetails.insertUmsMemberBalanceDetail(any())).thenReturn(1);

        // 本店按冻结人头费 ¥8 × 2 人;原来要从主理人份额里付 16.00。
        ClubSessionMerchantFee.Charge charge = mock(ClubSessionMerchantFee.Charge.class);
        when(charge.getMerchantMemberId()).thenReturn(SHOP);
        when(charge.getContractType()).thenReturn("MERCHANT_CHAPTER_OFFER");
        when(charge.getOrderId()).thenReturn(400L);
        when(charge.getChapterId()).thenReturn(100L);
        when(charge.heads()).thenReturn(2);
        when(charge.getFee()).thenReturn(new BigDecimal("8.00"));
        when(charge.total()).thenReturn(new BigDecimal("16.00"));
        plan = mock(ClubSessionMerchantFee.Plan.class);
        when(plan.getCharges()).thenReturn(List.of(charge));
        when(plan.isEmpty()).thenReturn(false);
        when(plan.total()).thenReturn(new BigDecimal("16.00"));
        when(plan.byMerchant()).thenReturn(Map.of(SHOP, new BigDecimal("16.00")));
        when(fees.plan(any(), eq(PRINCIPAL), isNull())).thenReturn(plan);
    }

    @Test
    void freeExploreSessionMerchantFeePlanIsEmpty() {
        ClubSessionMerchantFee.Plan feePlan = ReflectionTestUtils.invokeMethod(svc, "merchantFeePlan", activity, PRINCIPAL, null);

        assertTrue(feePlan.isEmpty());
        assertTrue(feePlan.getCharges().isEmpty());
        verify(fees, never()).plan(any(), any(), any());
    }

    @Test
    void revshareSessionDoesNotPayShopOrReducePrincipalButWritesReferenceRows() {
        assertEquals(1, svc.executeActivitySettlement());

        verify(members).addBalance(PRINCIPAL, new BigDecimal("97.00"));
        verify(members).addBalance(PLATFORM, new BigDecimal("3.00"));
        verify(members, never()).addBalance(eq(SHOP), any());
        verify(fees, never()).writeLedger(any(), any());
        verify(fees, never()).requireWithinPayeeShare(any(), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IFeReferenceSettlementService.RevshareLine>> lines = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<SessionPayeeResolver.Payer> payer = ArgumentCaptor.forClass(SessionPayeeResolver.Payer.class);
        verify(reference, times(1)).recordRevshareHeads(eq(activity), eq(topic), payer.capture(), lines.capture(), isNull());
        IFeReferenceSettlementService.RevshareLine line = lines.getValue().get(0);
        assertEquals(SHOP, line.getMerchantId());
        assertEquals(2, line.getHeads());
        assertEquals(new BigDecimal("8.00"), line.getUnitAmount());
        assertEquals(new BigDecimal("16.00"), line.getAmount());
        assertEquals(Long.valueOf(100L), line.getChapterId());
        assertEquals(FeReferenceSettlement.PAYER_CLUB, payer.getValue().getKind());
        assertEquals(CLUB, payer.getValue().getId());
        assertEquals(PRINCIPAL, payer.getValue().getMemberId());
    }

    @Test
    void nonFreeExploreRevshareSessionStillPaysShopOnline() {
        topic.setProductType(1);

        assertEquals(1, svc.executeActivitySettlement());

        verify(fees).writeLedger(activity, plan);
        verify(members).addBalance(SHOP, new BigDecimal("16.00"));
        verify(members).addBalance(PRINCIPAL, new BigDecimal("81.00"));
        verify(reference, never()).recordRevshareHeads(any(), any(), any(), any(), any());
    }
}
