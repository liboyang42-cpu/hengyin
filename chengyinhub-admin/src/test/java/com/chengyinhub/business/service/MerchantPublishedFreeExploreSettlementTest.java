package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ActivitySettlementHoldMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.support.ClubPrincipalPayee;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-13 业主:商家发布(topic.club_id 为空)的自由探索,承办俱乐部开的场次走同一条场次结算,
 * 票款给发布商家(topic.member_id);平台费仍按冻结费率;俱乐部发布的主题口径不变;
 * 旧的非场次路径(无承办俱乐部场次)仍然禁止。
 */
class MerchantPublishedFreeExploreSettlementTest {

    private static final Long ACTIVITY_ID = 100L;
    private static final Long CLUB_ID = 7002L;
    private static final Long CREATOR = 9L;        // 开场时的主理人(已转让出去)
    private static final Long PRINCIPAL = 11L;     // 俱乐部当前主理人
    private static final Long PLATFORM = 8L;
    private static final Long MERCHANT = 21L;     // 发布主题的商家
    private static final Long TOPIC_ID = 600L;

    private CmsActivityMapper cmsActivityMapper;
    private CmsRegistrationMapper cmsRegistrationMapper;
    private ClubMapper clubMapper;
    private IUmsMemberService umsMemberService;
    private SettlementDebtLedger debtLedger;
    private CmsActivityServiceImpl svc;
    private CmsActivity activity;
    private CmsTopicMapper topicMapper;
    private ISettlementEntryService entries;
    private com.chengyinhub.business.domain.CmsTopic topic;

    @BeforeEach
    void setUp() {
        cmsActivityMapper = mock(CmsActivityMapper.class);
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        clubMapper = mock(ClubMapper.class);
        umsMemberService = mock(IUmsMemberService.class);
        debtLedger = mock(SettlementDebtLedger.class);
        CmsSettingMapper settingMapper = mock(CmsSettingMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        IUmsMemberBalanceDetailService balanceDetails = mock(IUmsMemberBalanceDetailService.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        ClubPrincipalPayee payee = new ClubPrincipalPayee();
        ReflectionTestUtils.setField(payee, "clubMapper", clubMapper);

        svc = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", cmsActivityMapper);
        ReflectionTestUtils.setField(svc, "cmsSettingMapper", settingMapper);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", cmsRegistrationMapper);
        topicMapper = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topicMapper);
        entries = mock(ISettlementEntryService.class);
        ReflectionTestUtils.setField(svc, "settlementEntryService", entries);
        com.chengyinhub.business.mapper.TopicSaleSnapshotMapper snapshots =
                mock(com.chengyinhub.business.mapper.TopicSaleSnapshotMapper.class);
        when(snapshots.selectSnapshotByTopicId(TOPIC_ID)).thenReturn(TopicSaleCanonicalTestFixture.ordinarySnapshot(TOPIC_ID));
        ReflectionTestUtils.setField(svc, "topicSaleSnapshotMapper", snapshots);
        ReflectionTestUtils.setField(svc, "settlementDetailMapper", mock(SettlementDetailMapper.class));
        ReflectionTestUtils.setField(svc, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailService", balanceDetails);
        ReflectionTestUtils.setField(svc, "settlementRuleService", ruleService);
        ReflectionTestUtils.setField(svc, "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(svc, "transactionManager", tm);
        ReflectionTestUtils.setField(svc, "activitySettlementHoldMapper", mock(ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(svc, "settlementDebtLedger", debtLedger);
        ReflectionTestUtils.setField(svc, "clubPrincipalPayee", payee);
        // 第十二轮 k5:俱乐部场次要装配商家核销费组件;本类场次没有团码记录 ⇒ 不付商家费。
        com.chengyinhub.business.service.support.ClubSessionMerchantFee merchantFee =
                new com.chengyinhub.business.service.support.ClubSessionMerchantFee();
        ReflectionTestUtils.setField(merchantFee, "settlementDetailMapper",
                mock(com.chengyinhub.business.mapper.SettlementDetailMapper.class));
        // 无团码订单:mock 默认返回空列表 ⇒ 商家核销费计划为空
        ReflectionTestUtils.setField(merchantFee, "coopOrderMapper",
                mock(com.chengyinhub.business.mapper.CoopOrderMapper.class));
        ReflectionTestUtils.setField(svc, "clubSessionMerchantFee", merchantFee);

        activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setName("俱乐部场次");
        activity.setMemberId(CREATOR);
        activity.setClubId(CLUB_ID);
        activity.setTopicId(TOPIC_ID);
        topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setProductType(2);
        topic.setMemberId(MERCHANT);
        topic.setClubId(null);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        CmsRegistration paid = new CmsRegistration();
        paid.setId(5001L);
        paid.setPaymentStatus(2);
        paid.setRegistrationStatus(2);
        when(cmsRegistrationMapper.selectPaidUnrefundedIdsByActivityId(ACTIVITY_ID)).thenReturn(Collections.singletonList(5001L));
        when(cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(5001L)).thenReturn(paid);
        when(cmsActivityMapper.selectActivitiesForSettlement()).thenReturn(Collections.singletonList(activity));
        when(cmsActivityMapper.claimActivityForSettlement(ACTIVITY_ID)).thenReturn(1);
        when(cmsRegistrationMapper.selectTotalSalesByActivityId(ACTIVITY_ID)).thenReturn(new BigDecimal("100.00"));

        CmsSetting setting = new CmsSetting();
        setting.setPlatformMemberId(PLATFORM);
        when(settingMapper.selectCmsSettingById(1L)).thenReturn(setting);
        when(umsMemberService.lockMemberById(PLATFORM)).thenReturn(PLATFORM);
        when(umsMemberService.selectUmsMemberById(anyLong())).thenReturn(usableMember());
        when(umsMemberService.addBalance(anyLong(), any())).thenReturn(true);
        when(balanceDetails.insertUmsMemberBalanceDetail(any())).thenReturn(1);

        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.03"));
        rule.setMerchantRate(new BigDecimal("0.97"));
        rule.setCreatorRate(BigDecimal.ZERO);
        rule.setInviterRate(BigDecimal.ZERO);
        when(ruleService.getActiveRule(0L, "activity")).thenReturn(rule);
    }

    private static UmsMember usableMember() {
        UmsMember m = new UmsMember();
        m.setStatus(1L);
        m.setDelFlag(0);
        m.setBalance(BigDecimal.ZERO);
        return m;
    }

    @Test
    void merchantPublishedSessionSettlesToPublishingMerchant() {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(PRINCIPAL);
        when(clubMapper.selectClubById(CLUB_ID)).thenReturn(club);

        assertEquals(1, svc.executeActivitySettlement());

        verify(umsMemberService).addBalance(MERCHANT, new BigDecimal("97.00"));
        verify(umsMemberService).addBalance(PLATFORM, new BigDecimal("3.00"));
        verify(umsMemberService, never()).addBalance(eq(PRINCIPAL), any());
        verify(umsMemberService, never()).addBalance(eq(CREATOR), any());
        verify(debtLedger).offsetFromSettlement(MERCHANT, new BigDecimal("97.00"), ACTIVITY_ID);
        verify(entries).recordClubFreeExploreSessionSettlement(any(CmsRegistration.class), eq(MERCHANT));
    }

    @Test
    void clubPublishedSessionStillPaysClubPrincipal() {
        topic.setClubId(CLUB_ID);
        topic.setMemberId(CREATOR);
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(PRINCIPAL);
        when(clubMapper.selectClubById(CLUB_ID)).thenReturn(club);

        assertEquals(1, svc.executeActivitySettlement());

        verify(umsMemberService).addBalance(PRINCIPAL, new BigDecimal("97.00"));
        verify(umsMemberService, never()).addBalance(eq(MERCHANT), any());
        verify(entries).recordClubFreeExploreSessionSettlement(any(CmsRegistration.class), eq(PRINCIPAL));
    }

    @Test
    void merchantTopicWithoutHostingClubSessionStaysForbidden() {
        activity.setClubId(null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, svc::executeActivitySettlement);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
        verify(entries, never()).recordClubFreeExploreSessionSettlement(any(), any());
    }

    @Test
    void merchantTopicWithoutPublisherStaysForbidden() {
        topic.setMemberId(null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, svc::executeActivitySettlement);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void selectionAddsMerchantPublishedSessionsWithoutDuplicates() {
        when(cmsActivityMapper.selectActivitiesForSettlement()).thenReturn(Collections.emptyList());
        when(cmsActivityMapper.selectMerchantPublishedFreeExploreActivitiesForSettlement())
                .thenReturn(java.util.Arrays.asList(activity, activity));

        assertEquals(1, svc.selectActivitiesForSettlement().size());
    }
}
