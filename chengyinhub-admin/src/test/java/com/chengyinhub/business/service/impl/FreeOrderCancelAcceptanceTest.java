package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IRefundPayoutService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.ITeamGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SC-EXIT-ZERO 验收:玩家在订单详情(scene-member-order-detail → /api/registration/cancel-refund)
 * 取消一笔<b>真正免费</b>(0 现金、0 积分、单票无 parentOrderNo)的场次单。
 *
 * <p>期望:报名退款权 CAS 领取 → 库存按票数回补一次 → 权益容量(资格)释放;
 * 不建退款单、不打款、不加积分;重复取消不会二次回补库存。
 */
class FreeOrderCancelAcceptanceTest {

    private static final long REG_ID = 51001L;
    private static final long MEMBER = 61001L;
    private static final long TICKET = 71001L;
    private static final long ACTIVITY = 81001L;

    private CmsRegistrationMapper regMapper;
    private OmsTicketMapper ticketMapper;
    private UmsMemberMapper memberMapper;
    private IRefundService refundService;
    private IRefundPayoutService payoutService;
    private IPerkLedgerService perkLedger;
    private CmsRegistration registration;
    private TeamGroupServiceImpl service;

    @BeforeEach
    void setUp() {
        registration = new CmsRegistration();
        registration.setId(REG_ID);
        registration.setMemberId(MEMBER);
        registration.setOwnerType(2L);
        registration.setOwnerId(ACTIVITY);
        registration.setTicketId(TICKET);
        registration.setOrderNum(2L);
        registration.setPaymentStatus(2);
        registration.setRegistrationStatus(2);
        registration.setVerificationStatus(0);
        registration.setPayableAmount(BigDecimal.ZERO);
        registration.setWechatPaymentAmount(BigDecimal.ZERO);
        registration.setPointUsed(0L);
        // parentOrderNo 留空 ⇒ 单票路径 refundOne

        regMapper = mock(CmsRegistrationMapper.class);
        when(regMapper.selectCmsRegistrationById(REG_ID)).thenReturn(registration);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG_ID)).thenReturn(registration);
        ticketMapper = mock(OmsTicketMapper.class);
        when(ticketMapper.restoreInventory(eq(TICKET), anyLong())).thenReturn(1);
        memberMapper = mock(UmsMemberMapper.class);
        refundService = mock(IRefundService.class);
        payoutService = mock(IRefundPayoutService.class);
        perkLedger = mock(IPerkLedgerService.class);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setStartDate(new Date(System.currentTimeMillis() + 3L * 24 * 3600 * 1000));
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activityMapper.selectCmsActivityByIdForUpdate(ACTIVITY)).thenReturn(activity);

        service = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "refundService", refundService);
        ReflectionTestUtils.setField(service, "refundPayoutService", payoutService);
        ReflectionTestUtils.setField(service, "perkLedgerService", perkLedger);
        ReflectionTestUtils.setField(service, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(service, "firstReviewPointsClawback",
                mock(FirstReviewPointsClawbackService.class));
        com.chengyinhub.business.service.CancellationPhaseTestSupport.wireTeamTransactions(service,
                com.chengyinhub.business.service.CancellationPhaseTestSupport.memoryTransactions());
    }

    @Test
    void freeSingleOrderCancelReturnsStockAndEligibilityWithoutMoneyOrPoints() {
        when(regMapper.claimRegistrationRefunded(eq(REG_ID), anyString(), any(Date.class))).thenReturn(1);

        int result = service.cancelPaidRegistration(REG_ID, MEMBER);

        assertEquals(ITeamGroupService.CANCEL_REFUNDED, result, "免费单取消应直接完成,不转人工");
        verify(regMapper).claimRegistrationRefunded(eq(REG_ID), eq("auto-refund"), any(Date.class));
        verify(ticketMapper, times(1)).restoreInventory(TICKET, 2L);
        verify(perkLedger, times(1)).releaseOnRefund(eq(REG_ID), anyString());
        // 0 现金:不建退款单、不派发打款
        verify(refundService, never()).saveRefund(any());
        verify(refundService, never()).reviewRefund(any(), any());
        verify(payoutService, never()).payoutAfterReview(anyLong());
        // 0 积分:不 CAS 积分标记、不加积分
        verify(regMapper, never()).claimPointsReturn(anyLong(), anyString(), any());
        verify(memberMapper, never()).addPoints(anyLong(), anyInt());
    }

    /** 两次取消读到同一已支付快照(连点/并发):只有 CAS winner 回补库存与释放资格。 */
    @Test
    void repeatedCancelOnStaleSnapshotDoesNotRestoreStockTwice() {
        AtomicBoolean claimed = new AtomicBoolean();
        when(regMapper.claimRegistrationRefunded(eq(REG_ID), anyString(), any(Date.class)))
                .thenAnswer(inv -> claimed.compareAndSet(false, true) ? 1 : 0);
        // loser 当前读:已退款(payment_status=4)⇒ 视为已被处理,静默返回
        CmsRegistration current = new CmsRegistration();
        org.springframework.beans.BeanUtils.copyProperties(registration, current);
        current.setId(REG_ID);
        current.setPaymentStatus(4);
        current.setRegistrationStatus(3);
        current.setVerificationStatus(0);
        // 每次先经原主题/报名 fence，再经 refundOne 当前读；只有第二次 claim loser 的复读看到已退款。
        org.mockito.Mockito.doReturn(registration, registration, registration, registration, current)
                .when(regMapper).selectCmsRegistrationByIdForUpdate(REG_ID);

        service.cancelPaidRegistration(REG_ID, MEMBER);
        service.cancelPaidRegistration(REG_ID, MEMBER);

        verify(regMapper, times(2)).claimRegistrationRefunded(eq(REG_ID), anyString(), any(Date.class));
        verify(ticketMapper, times(1)).restoreInventory(TICKET, 2L);
        verify(perkLedger, times(1)).releaseOnRefund(eq(REG_ID), anyString());
        verify(refundService, never()).saveRefund(any());
        verify(memberMapper, never()).addPoints(anyLong(), anyInt());
    }

    /** 取消落库后(payment_status=4 / registration_status=3)再点取消:前置闸拒绝,不再碰库存。 */
    @Test
    void cancelAfterCommittedCancellationIsRefusedWithoutTouchingStock() {
        when(regMapper.claimRegistrationRefunded(eq(REG_ID), anyString(), any(Date.class))).thenReturn(1);
        service.cancelPaidRegistration(REG_ID, MEMBER);
        registration.setPaymentStatus(4);
        registration.setRegistrationStatus(3);

        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistration(REG_ID, MEMBER));

        assertEquals("仅已支付报名可取消退款", refused.getMessage());
        verify(ticketMapper, times(1)).restoreInventory(eq(TICKET), anyLong());
        verify(regMapper, times(1)).claimRegistrationRefunded(eq(REG_ID), anyString(), any(Date.class));
    }
}
