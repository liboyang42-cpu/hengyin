package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ActivitySettlementHold;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.vo.ClubSessionFeeRegistration;
import com.chengyinhub.business.mapper.ActivitySettlementHoldMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubSessionMerchantFeeMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.ISettlementClaimService;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.support.ClubPrincipalPayee;
import com.chengyinhub.business.service.support.ClubSessionMerchantFee;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第十二轮 k5(2026-11-05 业主):俱乐部场次上按核销付费的商家,场次结算时按参加人数拿冻结单价,
 * 从主理人份额里出,平台仍是留存销售额的 3%。数字照 h4 走查:¥39 × 4 人,一人退一半(留存 19.50),
 * 三人参加,商家 ¥8/人。旧代码上:商家一分没有,主理人拿 132.40。
 */
class ClubSessionMerchantFeeSettlementTest {

    private static final long ACTIVITY = 9L, TOPIC = 990015L, CLUB = 990004L;
    private static final long PRINCIPAL = 990005L, MERCHANT = 9002L, PLATFORM = 9001L, ORDER = 1L;

    private CmsActivityMapper activities;
    private CmsRegistrationMapper registrations;
    private ClubSessionMerchantFeeMapper feeMapper;
    private CoopOrderMapper orders;
    private SettlementDetailMapper details;
    private ISettlementClaimService claims;
    private IUmsMemberService members;
    private IUmsMemberBalanceDetailService balanceDetails;
    private ActivitySettlementHoldMapper holds;
    private AlarmService alarms;
    private CmsActivityServiceImpl svc;
    private CoopOrder order;
    private final List<SettlementDetail> inserted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        activities = mock(CmsActivityMapper.class);
        registrations = mock(CmsRegistrationMapper.class);
        feeMapper = mock(ClubSessionMerchantFeeMapper.class);
        orders = mock(CoopOrderMapper.class);
        details = mock(SettlementDetailMapper.class);
        claims = mock(ISettlementClaimService.class);
        members = mock(IUmsMemberService.class);
        balanceDetails = mock(IUmsMemberBalanceDetailService.class);
        holds = mock(ActivitySettlementHoldMapper.class);
        alarms = mock(AlarmService.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        CmsSettingMapper settings = mock(CmsSettingMapper.class);
        CmsSetting setting = new CmsSetting();
        setting.setPlatformMemberId(PLATFORM);
        when(settings.selectCmsSettingById(1L)).thenReturn(setting);
        ISettlementRuleService rules = mock(ISettlementRuleService.class);
        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.03"));
        rule.setMerchantRate(new BigDecimal("0.97"));
        rule.setCreatorRate(BigDecimal.ZERO);
        when(rules.getActiveRule(0L, "activity")).thenReturn(rule);
        ClubMapper clubs = mock(ClubMapper.class);
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(PRINCIPAL);
        when(clubs.selectClubById(CLUB)).thenReturn(club);
        ClubPrincipalPayee payee = new ClubPrincipalPayee();
        ReflectionTestUtils.setField(payee, "clubMapper", clubs);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(1);
        topic.setMemberId(PRINCIPAL);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);

        ClubSessionMerchantFee fee = new ClubSessionMerchantFee();
        ReflectionTestUtils.setField(fee, "feeMapper", feeMapper);
        ReflectionTestUtils.setField(fee, "coopOrderMapper", orders);
        ReflectionTestUtils.setField(fee, "settlementDetailMapper", details);
        ReflectionTestUtils.setField(fee, "settlementClaimService", claims);
        ReflectionTestUtils.setField(fee, "alarmService", alarms);

        svc = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(svc, "cmsSettingMapper", settings);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(svc, "settlementDetailMapper", details);
        ReflectionTestUtils.setField(svc, "umsMemberService", members);
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailService", balanceDetails);
        ReflectionTestUtils.setField(svc, "settlementRuleService", rules);
        ReflectionTestUtils.setField(svc, "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(svc, "transactionManager", tm);
        ReflectionTestUtils.setField(svc, "activitySettlementHoldMapper", holds);
        ReflectionTestUtils.setField(svc, "settlementDebtLedger", mock(SettlementDebtLedger.class));
        ReflectionTestUtils.setField(svc, "clubPrincipalPayee", payee);
        ReflectionTestUtils.setField(svc, "clubSessionMerchantFee", fee);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setName("外滩钟声 · 周六场");
        activity.setMemberId(PRINCIPAL);
        activity.setClubId(CLUB);
        activity.setTopicId(TOPIC);
        when(activities.selectActivitiesForSettlement()).thenReturn(Collections.singletonList(activity));
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activities.claimActivityForSettlement(ACTIVITY)).thenReturn(1);
        when(holds.selectReleasableHolds()).thenReturn(Collections.emptyList());
        // 4 × 39:三人全款未退,一人退一半 ⇒ 销售额 117.00 + 留存 19.50 = 136.50
        when(registrations.selectTotalSalesByActivityId(ACTIVITY)).thenReturn(new BigDecimal("117.00"));
        when(registrations.selectRetainedSalesAfterPartialRefundByActivityId(ACTIVITY)).thenReturn(new BigDecimal("19.50"));

        when(members.lockMemberById(PLATFORM)).thenReturn(PLATFORM);
        UmsMember usable = new UmsMember();
        usable.setStatus(1L);
        usable.setDelFlag(0);
        usable.setBalance(BigDecimal.ZERO);
        when(members.selectUmsMemberById(anyLong())).thenReturn(usable);
        when(members.addBalance(anyLong(), any())).thenReturn(true);
        when(balanceDetails.insertUmsMemberBalanceDetail(any())).thenReturn(1);

        CoopGroupVerify scan = new CoopGroupVerify();
        scan.setId(5L);
        scan.setTopicId(TOPIC);
        scan.setActivityId(ACTIVITY);
        scan.setCoopOrderId(ORDER);
        scan.setMerchantMemberId(MERCHANT);
        when(feeMapper.selectGroupVerifiesByActivity(ACTIVITY)).thenReturn(List.of(scan));
        order = new CoopOrder();
        order.setId(ORDER);
        order.setTopicId(TOPIC);
        order.setToType("merchant");
        order.setToId(MERCHANT);
        terms(2, "8.00", "8.00");
        when(orders.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(List.of(order));
        when(feeMapper.selectFeeRegistrationsForUpdate(eq(ACTIVITY), isNull()))
                .thenReturn(List.of(reg(74L), reg(75L), reg(78L)));
        for (long id : new long[]{74L, 75L, 76L, 78L}) {
            when(feeMapper.selectPayeeLinesForUpdate(id)).thenReturn(List.of(payeeLine(id)));
        }
        when(feeMapper.reducePayeeLine(anyLong(), any())).thenReturn(1);
        when(claims.claim(any())).thenAnswer(call -> {
            SettlementClaim claim = call.getArgument(0);
            claim.setId(1000L + claim.getFulfillmentId());
            return claim;
        });
        when(details.insertAdjustment(any())).thenAnswer(call -> {
            inserted.add(call.getArgument(0));
            return 1;
        });
    }

    private void terms(int mode, String frozenFee, String columnFee) {
        order.setShareMode(mode);
        order.setFixedFee(columnFee == null ? null : new BigDecimal(columnFee));
        String comp = mode == 0 ? "NONE" : mode == 1 ? "SALES_PERCENT" : "FIXED_PER_FULFILLMENT";
        order.setTermsSnapshot("{\"shareMode\":" + mode + ",\"compensationMode\":\"" + comp + "\""
                + (frozenFee == null ? "" : ",\"fixedFee\":" + frozenFee) + (mode == 1 ? ",\"shareRate\":10" : "") + "}");
    }

    private static ClubSessionFeeRegistration reg(long id) {
        ClubSessionFeeRegistration r = new ClubSessionFeeRegistration();
        r.setRegistrationId(id);
        r.setOrderNum(1L);
        r.setVerifiedSeats(0);
        return r;
    }

    private static SettlementDetail payeeLine(long registrationId) {
        SettlementDetail d = new SettlementDetail();
        d.setId(500L + registrationId);
        d.setMerchantId(PRINCIPAL);
        d.setReceiverType("merchant");
        d.setReceiverId(PRINCIPAL);
        d.setSettlementAmount(new BigDecimal("37.83"));
        d.setGrossAmount(new BigDecimal("37.83"));
        d.setStatus(0);
        return d;
    }

    @Test
    void fixedFeeIsPaidPerParticipatingPlayerFromThePrincipalShare() {
        assertEquals(1, svc.executeActivitySettlement());

        // 136.50:平台 4.10(3%),商家 3 × 8 = 24.00,主理人 132.40 − 24.00 = 108.40,三份合计 136.50
        verify(members).addBalance(PLATFORM, new BigDecimal("4.10"));
        verify(members).addBalance(MERCHANT, new BigDecimal("24.00"));
        verify(members).addBalance(PRINCIPAL, new BigDecimal("108.40"));
        ArgumentCaptor<UmsMemberBalanceDetail> flows = ArgumentCaptor.forClass(UmsMemberBalanceDetail.class);
        verify(balanceDetails, times(3)).insertUmsMemberBalanceDetail(flows.capture());
        UmsMemberBalanceDetail merchantFlow = flows.getAllValues().stream()
                .filter(f -> f.getMemberId() == MERCHANT).findFirst().orElseThrow();
        assertEquals(ClubSessionMerchantFee.EVENT_TYPE_SESSION_MERCHANT_FEE, merchantFlow.getEventType());
        assertEquals(ACTIVITY, merchantFlow.getEventId());
        // 账本:每张计费报名主理人行减 8,商家落一条 coop_merchant 行(各有 claim),之后整场置已结算
        for (long id : new long[]{74L, 75L, 78L}) {
            verify(feeMapper).reducePayeeLine(500L + id, new BigDecimal("8.00"));
        }
        verify(feeMapper, never()).reducePayeeLine(eq(576L), any());
        assertEquals(3, inserted.size());
        for (SettlementDetail line : inserted) {
            assertEquals(ClubSessionMerchantFee.RECEIVER_TYPE, line.getReceiverType());
            assertEquals(MERCHANT, line.getReceiverId());
            assertEquals(0, new BigDecimal("8.00").compareTo(line.getSettlementAmount()));
            assertEquals(1000L + line.getSourceId(), line.getClaimId());
            assertEquals(0, line.getStatus());
        }
        ArgumentCaptor<SettlementClaim> claim = ArgumentCaptor.forClass(SettlementClaim.class);
        verify(claims, times(3)).claim(claim.capture());
        assertEquals("COOP_ORDER", claim.getValue().getSourceContractType());
        assertEquals(ORDER, claim.getValue().getSourceContractId());
        verify(details).markActivityRegistrationDetailsSettled(ACTIVITY);
    }

    @Test
    void drainageTermsPayNothingAndThePrincipalKeepsTheShare() {
        terms(0, null, null);
        assertEquals(1, svc.executeActivitySettlement());
        verify(members).addBalance(PRINCIPAL, new BigDecimal("132.40"));
        verify(members, never()).addBalance(eq(MERCHANT), any());
        verify(feeMapper, never()).reducePayeeLine(anyLong(), any());
    }

    @Test
    void noGroupScanMeansNoFee() {
        when(feeMapper.selectGroupVerifiesByActivity(ACTIVITY)).thenReturn(List.of());
        assertEquals(1, svc.executeActivitySettlement());
        verify(members).addBalance(PRINCIPAL, new BigDecimal("132.40"));
        verify(members, never()).addBalance(eq(MERCHANT), any());
    }

    @Test
    void percentageTermsRefuseTheSessionWithAnAlarmAndMoveNoMoney() {
        terms(1, null, null);
        order.setShareRate(new BigDecimal("10"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, svc::executeActivitySettlement);
        assertTrue(failure.getMessage().contains("CLUB_SESSION_PERCENT_TERMS_UNSUPPORTED"), failure.getMessage());
        verify(members, never()).addBalance(anyLong(), any());
        verify(alarms).alarm(eq("俱乐部场次商家核销费拒结"), any());
    }

    @Test
    void feeAboveThePrincipalShareRefusesTheSession() {
        terms(2, "45.00", "45.00"); // 3 × 45 = 135 > 主理人份额 132.40
        IllegalStateException failure = assertThrows(IllegalStateException.class, svc::executeActivitySettlement);
        assertTrue(failure.getMessage().contains("MERCHANT_FEE_EXCEEDS_PRINCIPAL_SHARE"), failure.getMessage());
        verify(members, never()).addBalance(anyLong(), any());
        verify(alarms, atLeastOnce()).alarm(any(), any());
    }

    @Test
    void feeAboveOnePlayersPrincipalLineRefusesTheSession() {
        when(feeMapper.reducePayeeLine(eq(575L), any())).thenReturn(0);
        IllegalStateException failure = assertThrows(IllegalStateException.class, svc::executeActivitySettlement);
        assertTrue(failure.getMessage().contains("MERCHANT_FEE_EXCEEDS_PRINCIPAL_LINE"), failure.getMessage());
    }

    @Test
    void frozenFeeThatDisagreesWithTheOrderRefuses() {
        terms(2, "8.00", "9.00");
        IllegalStateException failure = assertThrows(IllegalStateException.class, svc::executeActivitySettlement);
        assertTrue(failure.getMessage().contains("FROZEN_FIXED_FEE_UNPROVEN"), failure.getMessage());
        verify(members, never()).addBalance(anyLong(), any());
    }

    @Test
    void merchantWhoIsThePrincipalIsNotPaidTwice() {
        order.setToId(PRINCIPAL);
        CoopGroupVerify scan = feeMapper.selectGroupVerifiesByActivity(ACTIVITY).get(0);
        scan.setMerchantMemberId(PRINCIPAL);
        assertEquals(1, svc.executeActivitySettlement());
        verify(members).addBalance(PRINCIPAL, new BigDecimal("132.40"));
        verify(feeMapper, never()).reducePayeeLine(anyLong(), any());
    }

    @Test
    void heldRegistrationPaysItsFeeWhenTheComplaintCloses() {
        when(activities.selectActivitiesForSettlement()).thenReturn(Collections.emptyList());
        ActivitySettlementHold hold = new ActivitySettlementHold();
        hold.setId(3L);
        hold.setActivityId(ACTIVITY);
        hold.setRegistrationId(76L);
        when(holds.selectReleasableHolds()).thenReturn(List.of(hold));
        CmsRegistration held = new CmsRegistration();
        held.setId(76L);
        held.setPaymentStatus(2);
        held.setRegistrationStatus(2);
        held.setPayableAmount(new BigDecimal("39.00"));
        when(registrations.selectCmsRegistrationByIdForUpdate(76L)).thenReturn(held);
        when(holds.claimHold(3L, ActivitySettlementHold.RELEASED)).thenReturn(1);
        when(feeMapper.selectFeeRegistrationsForUpdate(ACTIVITY, 76L)).thenReturn(List.of(reg(76L)));

        svc.executeActivitySettlement();

        // 39:平台 1.17,商家 8.00,主理人 37.83 − 8.00 = 29.83
        verify(members).addBalance(PLATFORM, new BigDecimal("1.17"));
        verify(members).addBalance(MERCHANT, new BigDecimal("8.00"));
        verify(members).addBalance(PRINCIPAL, new BigDecimal("29.83"));
        verify(feeMapper).reducePayeeLine(576L, new BigDecimal("8.00"));
        verify(details).markRegistrationDetailsSettled(76L);
    }

    /** 结算后退款:既有冲销按比例连商家那份一起冲回,分毫不差(全额退、退一半各一次)。 */
    @Test
    void refundAfterSettlementClawsTheMerchantPartBackToTheFen() {
        List<SettlementDetail> lines = List.of(line("platform", 0L, "1.17"), line("merchant", PRINCIPAL, "29.83"),
                line(ClubSessionMerchantFee.RECEIVER_TYPE, MERCHANT, "8.00"));
        BigDecimal[][] full = RefundServiceImpl.allocateClawback(lines, refund("39.00", "39.00"), new BigDecimal("39.00"));
        assertMoney("1.17", full[0][2]);
        assertMoney("29.83", full[1][2]);
        assertMoney("8.00", full[2][2]);

        BigDecimal[][] half = RefundServiceImpl.allocateClawback(lines, refund("39.00", "19.50"), new BigDecimal("39.00"));
        assertMoney("0.59", half[0][2]);
        assertMoney("4.00", half[2][2]);
        assertMoney("14.91", half[1][2]); // 主理人取余数:19.50 − 0.59 − 4.00
    }

    private static SettlementDetail line(String type, long receiver, String amount) {
        SettlementDetail d = new SettlementDetail();
        d.setReceiverType(type);
        d.setReceiverId(receiver);
        d.setGrossAmount(new BigDecimal(amount));
        d.setCommissionAmount(BigDecimal.ZERO);
        d.setSettlementAmount(new BigDecimal(amount));
        d.setStatus(1);
        return d;
    }

    private static RefundApplication refund(String paid, String amount) {
        RefundApplication r = new RefundApplication();
        r.setPaidAmount(new BigDecimal(paid));
        r.setRefundAmount(new BigDecimal(amount));
        return r;
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }
}
