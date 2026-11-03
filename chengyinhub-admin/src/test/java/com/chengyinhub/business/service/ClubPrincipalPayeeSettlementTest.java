package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
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
 * 2026-11-02 pay-gate:俱乐部场次的现金给俱乐部<b>当前</b>主理人(人),不给开场时写死的创建人。
 * 旧代码按 cms_activity.member_id 入账:转让主理人后钱仍进前主理人账户,本测试在旧代码上红。
 */
class ClubPrincipalPayeeSettlementTest {

    private static final Long ACTIVITY_ID = 100L;
    private static final Long CLUB_ID = 7002L;
    private static final Long CREATOR = 9L;        // 开场时的主理人(已转让出去)
    private static final Long PRINCIPAL = 11L;     // 俱乐部当前主理人
    private static final Long PLATFORM = 8L;

    private CmsActivityMapper cmsActivityMapper;
    private CmsRegistrationMapper cmsRegistrationMapper;
    private ClubMapper clubMapper;
    private IUmsMemberService umsMemberService;
    private SettlementDebtLedger debtLedger;
    private CmsActivityServiceImpl svc;
    private CmsActivity activity;

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
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(svc, "settlementDetailMapper", mock(SettlementDetailMapper.class));
        ReflectionTestUtils.setField(svc, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailService", balanceDetails);
        ReflectionTestUtils.setField(svc, "settlementRuleService", ruleService);
        ReflectionTestUtils.setField(svc, "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(svc, "transactionManager", tm);
        ReflectionTestUtils.setField(svc, "activitySettlementHoldMapper", mock(ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(svc, "settlementDebtLedger", debtLedger);
        ReflectionTestUtils.setField(svc, "clubPrincipalPayee", payee);

        activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setName("俱乐部场次");
        activity.setMemberId(CREATOR);
        activity.setClubId(CLUB_ID);
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
    void clubSessionCreditsCurrentPrincipalNotCreator() {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(PRINCIPAL);
        when(clubMapper.selectClubById(CLUB_ID)).thenReturn(club);

        assertEquals(1, svc.executeActivitySettlement());

        verify(umsMemberService).addBalance(PRINCIPAL, new BigDecimal("97.00"));
        verify(umsMemberService).addBalance(PLATFORM, new BigDecimal("3.00"));
        verify(umsMemberService, never()).addBalance(eq(CREATOR), any());
        verify(debtLedger).offsetFromSettlement(PRINCIPAL, new BigDecimal("97.00"), ACTIVITY_ID);
    }

    @Test
    void clubWithoutPrincipalFailsClosedWithoutAnyCash() {
        when(clubMapper.selectClubById(CLUB_ID)).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, svc::executeActivitySettlement);

        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    /**
     * 2026-11-02 退款档位:集合前 24 小时内退了 50% 的报名置退款态,不在已付未退合计里;
     * 未退回的那一半(这里 4.95)必须补进结算销售额,否则主办方拿不到。旧代码只按 100.00 结算。
     */
    @Test
    void retainedHalfOfLateRefundIsSettledToOrganiser() {
        activity.setClubId(null);
        when(cmsRegistrationMapper.selectRetainedSalesAfterPartialRefundByActivityId(ACTIVITY_ID))
                .thenReturn(new BigDecimal("4.95"));

        assertEquals(1, svc.executeActivitySettlement());

        verify(umsMemberService).addBalance(CREATOR, new BigDecimal("101.80"));
        verify(umsMemberService).addBalance(PLATFORM, new BigDecimal("3.15"));
    }

    @Test
    void nonClubSessionStillCreditsCreator() {
        activity.setClubId(null);

        assertEquals(1, svc.executeActivitySettlement());

        verify(umsMemberService).addBalance(CREATOR, new BigDecimal("97.00"));
        verify(clubMapper, never()).selectClubById(anyLong());
    }
}
