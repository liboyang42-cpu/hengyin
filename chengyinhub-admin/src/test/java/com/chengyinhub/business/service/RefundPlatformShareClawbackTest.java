package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第十二轮 k11(2026-11-05 业主):结算后退款,平台也按比例退回它那份。
 * 活动路(含非俱乐部活动):平台份额从结算时实收 event_type=7 的平台会员余额扣回;
 * 主题路:平台分项从不入余额,只记冲销台账,不扣任何会员。
 */
class RefundPlatformShareClawbackTest {

    private static final long PLATFORM_MEMBER = 100000L;
    private SettlementDetailMapper details;
    private UmsMemberMapper members;
    private UmsMemberBalanceDetailMapper flows;
    private CmsActivityMapper activities;
    private CmsTopicMapper topics;
    private CoopOrderMapper coopOrders;
    private AlarmService alarms;
    private RefundServiceImpl svc;

    @BeforeEach
    void setUp() {
        details = mock(SettlementDetailMapper.class);
        members = mock(UmsMemberMapper.class);
        flows = mock(UmsMemberBalanceDetailMapper.class);
        activities = mock(CmsActivityMapper.class);
        topics = mock(CmsTopicMapper.class);
        coopOrders = mock(CoopOrderMapper.class);
        alarms = mock(AlarmService.class);
        svc = new RefundServiceImpl();
        ReflectionTestUtils.setField(svc, "settlementDetailMapper", details);
        ReflectionTestUtils.setField(svc, "umsMemberMapper", members);
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailMapper", flows);
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(svc, "coopOrderMapper", coopOrders);
        ReflectionTestUtils.setField(svc, "activitySettlementHoldMapper",
                mock(com.chengyinhub.business.mapper.ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(svc, "settlementDebtLedger", mock(SettlementDebtLedger.class));
        ReflectionTestUtils.setField(svc, "alarmService", alarms);
        when(details.insertAdjustment(any())).thenReturn(1);
        when(members.lockMemberById(anyLong())).thenAnswer(i -> i.getArgument(0));
        when(members.deductBalance(anyLong(), any())).thenReturn(1);
        when(flows.insertUmsMemberBalanceDetail(any())).thenReturn(1);
        UmsMember rich = new UmsMember();
        rich.setBalance(new BigDecimal("1000.00"));
        when(members.selectUmsMemberById(anyLong())).thenReturn(rich);
    }

    /** 非俱乐部活动 ¥97.50,平台 3%(2.93)、商家 94.57;全额退:平台会员 −2.93,商家 −94.57。 */
    @Test
    void standaloneActivityRefundAfterSettlementDebitsPlatformMemberWhoWasPaid() throws Exception {
        CmsRegistration reg = registration(2L, 77L);
        CmsActivity activity = new CmsActivity();
        activity.setId(77L);
        activity.setIsSettlement(1);
        when(activities.selectCmsActivityById(77L)).thenReturn(activity);
        when(details.selectSettledDetails("registration", 10L)).thenReturn(Arrays.asList(
                detail("merchant", 5L, "94.57"), detail("platform", 0L, "2.93")));
        when(flows.selectIncomeMemberIdsByEventTypeAndEventId(3L, 77L)).thenReturn(List.of(5L));
        when(flows.selectIncomeMemberIdsByEventTypeAndEventId(7L, 77L)).thenReturn(List.of(PLATFORM_MEMBER));

        clawBack(reg, "97.50", "97.50");

        verify(members).deductBalance(eq(PLATFORM_MEMBER), eq(new BigDecimal("2.93")));
        verify(members).deductBalance(eq(5L), eq(new BigDecimal("94.57")));
        verify(members, never()).deductBalance(eq(0L), any());
    }

    /** 活动已结算但平台没收到分润(查不到 event_type=7 入账):不扣任何人,告警。 */
    @Test
    void platformLineWithoutPlatformIncomeIsLedgerOnlyAndAlarms() throws Exception {
        CmsRegistration reg = registration(2L, 77L);
        CmsActivity activity = new CmsActivity();
        activity.setId(77L);
        activity.setIsSettlement(1);
        when(activities.selectCmsActivityById(77L)).thenReturn(activity);
        when(details.selectSettledDetails("registration", 10L)).thenReturn(List.of(detail("platform", 0L, "2.93")));
        when(flows.selectIncomeMemberIdsByEventTypeAndEventId(7L, 77L)).thenReturn(Collections.emptyList());

        clawBack(reg, "97.50", "97.50");

        verify(details).insertAdjustment(any());
        verify(members, never()).deductBalance(anyLong(), any());
        verify(alarms).alarm(eq("退款冲销平台份额无实收人"), anyString());
    }

    @Test
    void ambiguousOrInvalidPlatformIncomeRefusesBeforeAnyDebit() {
        CmsRegistration reg = registration(2L, 77L);
        CmsActivity activity = new CmsActivity();
        activity.setId(77L);
        activity.setIsSettlement(1);
        when(activities.selectCmsActivityById(77L)).thenReturn(activity);
        when(details.selectSettledDetails("registration", 10L)).thenReturn(List.of(detail("platform", 0L, "2.93")));
        for (List<Long> recipients : List.of(List.of(PLATFORM_MEMBER, 9001L), List.of(0L))) {
            when(flows.selectIncomeMemberIdsByEventTypeAndEventId(7L, 77L)).thenReturn(recipients);

            InvocationTargetException error = assertThrows(InvocationTargetException.class,
                    () -> clawBack(reg, "97.50", "97.50"));
            assertInstanceOf(IllegalStateException.class, error.getCause());
        }
        verify(details, never()).insertAdjustment(any());
        verify(members, never()).deductBalance(anyLong(), any());
    }

    /** 主题报名(owner_type=1,无合作单):平台分项从不入余额 ⇒ 不扣平台会员。 */
    @Test
    void topicRegistrationPlatformLineNeverDebitsAMember() throws Exception {
        CmsRegistration reg = registration(1L, 55L);
        when(coopOrders.selectEffectiveOrdersByTopicId(55L)).thenReturn(Collections.emptyList());
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(55L);
        topic.setIsSettlement(1);
        topic.setMemberId(9L);
        when(topics.selectCmsTopicById(55L)).thenReturn(topic);
        when(details.selectSettledDetails("registration", 10L)).thenReturn(List.of(detail("platform", 0L, "2.93")));
        when(flows.selectIncomeMemberIdsByEventTypeAndEventId(eq(7L), anyLong())).thenReturn(List.of(PLATFORM_MEMBER));

        clawBack(reg, "97.50", "97.50");

        verify(members, never()).deductBalance(anyLong(), any());
    }

    private void clawBack(CmsRegistration reg, String paid, String refunded) throws Exception {
        RefundApplication refund = new RefundApplication();
        refund.setId(1L);
        refund.setSourceType("registration");
        refund.setSourceId(reg.getId());
        refund.setPaidAmount(new BigDecimal(paid));
        refund.setRefundAmount(new BigDecimal(refunded));
        Method m = RefundServiceImpl.class.getDeclaredMethod("clawBackSettledShares", RefundApplication.class, CmsRegistration.class);
        m.setAccessible(true);
        m.invoke(svc, refund, reg);
    }

    private static CmsRegistration registration(long ownerType, long ownerId) {
        CmsRegistration reg = new CmsRegistration();
        reg.setId(10L);
        reg.setOwnerType(ownerType);
        reg.setOwnerId(ownerId);
        reg.setPaymentStatus(2);
        return reg;
    }

    private static SettlementDetail detail(String receiverType, Long receiverId, String amount) {
        SettlementDetail s = new SettlementDetail();
        s.setId(receiverId + 1);
        s.setMerchantId(5L);
        s.setReceiverType(receiverType);
        s.setReceiverId(receiverId);
        s.setGrossAmount(new BigDecimal(amount));
        s.setCommissionAmount(BigDecimal.ZERO);
        s.setSettlementAmount(new BigDecimal(amount));
        s.setStatus(1);
        return s;
    }
}
