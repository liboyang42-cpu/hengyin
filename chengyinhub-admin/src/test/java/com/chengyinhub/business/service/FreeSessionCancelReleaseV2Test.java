package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService;
import com.chengyinhub.business.service.impl.TeamGroupServiceImpl;
import com.chengyinhub.common.constant.EntitlementInvalidReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CL-13 / CL-14:玩家自助 /api/registration/cancel-refund 的服务层真路径
 * (TeamGroupServiceImpl.cancelPaidRegistration)。
 *
 * <p>RefundPolicyTest 只守纯函数;这里驱动真实服务,证明:免费场次单集合前 24h 内仍能取消,
 * 且取消确实释放名额(票库存原子回补 + 报名退款权 CAS + 权益释放);已开始 / 已核销 / 付费单
 * 在 24h 内被拒,且绝不碰库存。
 */
class FreeSessionCancelReleaseV2Test {

    private static final Long REG = 8801L;
    private static final Long ACTIVITY = 8802L;
    private static final Long TICKET = 8803L;
    private static final Long MEMBER = 8804L;
    private static final long H = 3600_000L;

    private CmsRegistrationMapper regMapper;
    private OmsTicketMapper ticketMapper;
    private CmsActivityMapper activityMapper;
    private IPerkLedgerService perkLedgerService;
    private IRefundService refundService;
    private TeamGroupServiceImpl service;

    @BeforeEach
    void setUp() {
        regMapper = mock(CmsRegistrationMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        perkLedgerService = mock(IPerkLedgerService.class);
        service = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service, "perkLedgerService", perkLedgerService);
        ReflectionTestUtils.setField(service, "firstReviewPointsClawback", mock(FirstReviewPointsClawbackService.class));
        refundService = mock(IRefundService.class);
        ReflectionTestUtils.setField(service, "refundService", refundService);
        when(regMapper.claimRegistrationRefunded(eq(REG), anyString(), any(Date.class))).thenReturn(1);
        when(ticketMapper.restoreInventory(TICKET, 2L)).thenReturn(1);
    }

    private CmsRegistration sessionOrder(BigDecimal payable, long pointUsed, int verification) {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(2L);
        r.setOwnerId(ACTIVITY);
        r.setMemberId(MEMBER);
        r.setTicketId(TICKET);
        r.setOrderNum(2L);
        r.setPaymentStatus(2);
        r.setVerificationStatus(verification);
        r.setPayableAmount(payable);
        r.setWechatPaymentAmount(payable);
        r.setPointUsed(pointUsed);
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(r);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(r);
        return r;
    }

    private void activityStartsIn(long millis) {
        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY);
        a.setStartDate(new Date(System.currentTimeMillis() + millis));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(a);
    }

    /** CL-13:免费单集合前 2 小时(已在 24h 窗口内)取消成功,并回补 2 张票库存、释放权益。 */
    @Test
    void freeSessionOrderWithin24hCancelsAndReleasesPlaces() {
        sessionOrder(BigDecimal.ZERO, 0L, 0);
        activityStartsIn(2 * H);

        assertEquals(ITeamGroupService.CANCEL_REFUNDED, service.cancelPaidRegistration(REG, MEMBER));

        verify(regMapper).claimRegistrationRefunded(eq(REG), eq("auto-refund"), any(Date.class));
        verify(ticketMapper).restoreInventory(TICKET, 2L);
        verify(perkLedgerService).releaseOnRefund(REG, EntitlementInvalidReason.REFUND);
    }

    /** CL-13 负控:付费场次单同样在 24h 内 ⇒ 守集合前 24h 拒绝,库存不动。 */
    @Test
    void paidSessionOrderWithin24hRejectedWithoutTouchingInventory() {
        sessionOrder(new BigDecimal("9.90"), 0L, 0);
        activityStartsIn(2 * H);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.cancelPaidRegistration(REG, MEMBER));
        assertTrue(e.getMessage().contains("集合前24小时"), e.getMessage());
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
        verify(regMapper, never()).claimRegistrationRefunded(anyLong(), anyString(), any(Date.class));
    }

    /** CL-14:免费单场次已开始 ⇒ 拒绝取消,名额不释放。 */
    @Test
    void startedFreeSessionCannotBeCancelled() {
        sessionOrder(BigDecimal.ZERO, 0L, 0);
        activityStartsIn(-60_000L);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.cancelPaidRegistration(REG, MEMBER));
        assertEquals("场次已开始，不可取消报名", e.getMessage());
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
        verify(regMapper, never()).claimRegistrationRefunded(anyLong(), anyString(), any(Date.class));
        verify(perkLedgerService, never()).releaseOnRefund(anyLong(), anyString());
    }

    /** CL-14:已核销免费单即便还没开场也不能取消。 */
    @Test
    void verifiedFreeSessionCannotBeCancelledEvenBeforeStart() {
        sessionOrder(BigDecimal.ZERO, 0L, 1);
        activityStartsIn(2 * H);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.cancelPaidRegistration(REG, MEMBER));
        assertTrue(e.getMessage().contains("已核销"), e.getMessage());
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
        verify(regMapper, never()).claimRegistrationRefunded(anyLong(), anyString(), any(Date.class));
    }
}
