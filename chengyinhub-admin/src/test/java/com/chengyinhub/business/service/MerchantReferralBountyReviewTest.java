package com.chengyinhub.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.chengyinhub.business.domain.MerchantReferralBounty;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MerchantReferralBountyMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.rewardpledge.CashPayoutSwitch;
import com.chengyinhub.common.exception.ServiceException;

/**
 * R9 BE-13: by default an activated bounty goes to the operator queue (status 4) and pays nothing;
 * approval pays only with the money switch open; rejection is terminal and pays nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantReferralBountyReviewTest
{
    private static final long MERCHANT = 500L;
    private static final long INVITER = 100L;
    private static final long BOUNTY_ID = 7L;

    @Mock private MerchantReferralBountyMapper bountyMapper;
    @Mock private IUmsMemberService umsMemberService;
    @Mock private UmsMemberBalanceDetailMapper balanceDetailMapper;
    @Mock private UmsMemberMapper umsMemberMapper;
    @InjectMocks private MerchantReferralBountyService service;

    @BeforeEach
    void wire()
    {
        PlatformTransactionManager manager = Mockito.mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "transactionManager", manager);
        when(umsMemberMapper.selectBalanceForUpdate(anyLong())).thenReturn(BigDecimal.ZERO);
        when(bountyMapper.selectPaidIdsByInviterForUpdate(anyLong())).thenReturn(Collections.emptyList());
        UmsMember inviter = new UmsMember();
        inviter.setId(INVITER);
        inviter.setBalance(BigDecimal.ZERO);
        when(umsMemberService.selectUmsMemberById(INVITER)).thenReturn(inviter);
    }

    private static Date daysFromNow(int days)
    {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }

    private MerchantReferralBounty bounty(int status)
    {
        MerchantReferralBounty b = new MerchantReferralBounty();
        b.setId(BOUNTY_ID);
        b.setMerchantMemberId(MERCHANT);
        b.setInviterMemberId(INVITER);
        b.setMerchantRegisteredAt(daysFromNow(-10));
        b.setWindowExpiresAt(daysFromNow(20));
        b.setStatus(status);
        b.setVerifiedHeadsSnapshot("11,22,33");
        b.setBountyAmount(new BigDecimal("30.00"));
        return b;
    }

    private void moneySwitch(boolean open)
    {
        ReflectionTestUtils.setField(service, "cashPayoutSwitch", new CashPayoutSwitch(open, open, com.chengyinhub.business.moneyswitch.MoneyGateTestSupport.openGate()));
    }

    @Test
    void activationGoesToReviewQueueAndPaysNothingByDefault()
    {
        moneySwitch(true); // even with money on, auto-pay defaults to off
        MerchantReferralBounty pending = bounty(MerchantReferralBounty.STATUS_PENDING);
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pending);
        when(bountyMapper.selectByMerchantMemberIdForUpdate(MERCHANT)).thenReturn(pending);
        when(bountyMapper.countVerifiedHeadsByMerchantInWindow(eq(MERCHANT), any(), any())).thenReturn(3);
        when(bountyMapper.sumVerifiedPaidByMerchantInWindow(eq(MERCHANT), any(), any())).thenReturn(new BigDecimal("100.00"));
        when(bountyMapper.selectVerifiedRegistrationIdsByMerchantInWindow(eq(MERCHANT), any(), any(), anyInt()))
                .thenReturn(Arrays.asList(11L, 22L, 33L));
        when(bountyMapper.markPaidIfPending(anyLong(), any(), any(), any(), any(), any())).thenReturn(1);
        when(bountyMapper.markAwaitingReviewIfPending(eq(BOUNTY_ID), any(), eq("11,22,33"), eq(new BigDecimal("30.00")))).thenReturn(1);

        service.onVerification(MERCHANT);

        verify(bountyMapper).markAwaitingReviewIfPending(eq(BOUNTY_ID), any(), eq("11,22,33"), eq(new BigDecimal("30.00")));
        verify(bountyMapper, never()).markPaidIfPending(anyLong(), any(), any(), any(), any(), any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void autoPayStillQueuesWhileMoneySwitchIsClosed()
    {
        ReflectionTestUtils.setField(service, "autoPay", true);
        moneySwitch(false);
        MerchantReferralBounty pending = bounty(MerchantReferralBounty.STATUS_PENDING);
        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenReturn(pending);
        when(bountyMapper.selectByMerchantMemberIdForUpdate(MERCHANT)).thenReturn(pending);
        when(bountyMapper.countVerifiedHeadsByMerchantInWindow(eq(MERCHANT), any(), any())).thenReturn(3);
        when(bountyMapper.sumVerifiedPaidByMerchantInWindow(eq(MERCHANT), any(), any())).thenReturn(new BigDecimal("100.00"));
        when(bountyMapper.selectVerifiedRegistrationIdsByMerchantInWindow(eq(MERCHANT), any(), any(), anyInt()))
                .thenReturn(Arrays.asList(11L, 22L, 33L));
        when(bountyMapper.markPaidIfPending(anyLong(), any(), any(), any(), any(), any())).thenReturn(1);

        service.onVerification(MERCHANT);

        verify(bountyMapper).markAwaitingReviewIfPending(eq(BOUNTY_ID), any(), any(), any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void approveIsRefusedWithoutTouchingStateWhenMoneySwitchIsClosed()
    {
        moneySwitch(false);
        when(bountyMapper.selectById(BOUNTY_ID)).thenReturn(bounty(MerchantReferralBounty.STATUS_AWAITING_REVIEW));

        ServiceException e = assertThrows(ServiceException.class, () -> service.approve(BOUNTY_ID, "sys:1", "ok"));

        assertEquals(Integer.valueOf(409), e.getCode());
        verify(bountyMapper, never()).markPaidIfAwaitingReview(anyLong(), any(), any(), any(), any());
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void approvePaysTheRecordedAmountOnceWhenMoneySwitchIsOpen()
    {
        moneySwitch(true);
        MerchantReferralBounty awaiting = bounty(MerchantReferralBounty.STATUS_AWAITING_REVIEW);
        when(bountyMapper.selectById(BOUNTY_ID)).thenReturn(awaiting);
        when(bountyMapper.selectByMerchantMemberIdForUpdate(MERCHANT)).thenReturn(awaiting);
        when(bountyMapper.countStillValidAmong(Arrays.asList(11L, 22L, 33L), MERCHANT)).thenReturn(3);
        when(bountyMapper.markPaidIfAwaitingReview(eq(BOUNTY_ID), any(), any(), eq("sys:1"), eq("核对无误"))).thenReturn(1);

        service.approve(BOUNTY_ID, "sys:1", "  核对无误 ");

        verify(umsMemberService).addBalance(INVITER, new BigDecimal("30.00"));
    }

    @Test
    void approveRefusesWhenTheActivationNoLongerHolds()
    {
        moneySwitch(true);
        MerchantReferralBounty awaiting = bounty(MerchantReferralBounty.STATUS_AWAITING_REVIEW);
        when(bountyMapper.selectById(BOUNTY_ID)).thenReturn(awaiting);
        when(bountyMapper.selectByMerchantMemberIdForUpdate(MERCHANT)).thenReturn(awaiting);
        when(bountyMapper.countStillValidAmong(any(), eq(MERCHANT))).thenReturn(2);
        when(bountyMapper.markPaidIfAwaitingReview(anyLong(), any(), any(), any(), any())).thenReturn(1);

        assertThrows(ServiceException.class, () -> service.approve(BOUNTY_ID, "sys:1", null));
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void approveRefusesARowThatIsNotAwaitingReview()
    {
        moneySwitch(true);
        MerchantReferralBounty paid = bounty(MerchantReferralBounty.STATUS_PAID);
        when(bountyMapper.selectById(BOUNTY_ID)).thenReturn(paid);
        when(bountyMapper.selectByMerchantMemberIdForUpdate(MERCHANT)).thenReturn(paid);

        assertThrows(ServiceException.class, () -> service.approve(BOUNTY_ID, "sys:1", null));
        verify(umsMemberService, never()).addBalance(anyLong(), any());
    }

    @Test
    void rejectNeedsAReasonAndNeverPays()
    {
        when(bountyMapper.selectById(BOUNTY_ID)).thenReturn(bounty(MerchantReferralBounty.STATUS_AWAITING_REVIEW));
        assertEquals(Integer.valueOf(400), assertThrows(ServiceException.class,
                () -> service.reject(BOUNTY_ID, "sys:1", "  ")).getCode());

        when(bountyMapper.markRejectedIfOpen(BOUNTY_ID, "sys:1", "自扫自核")).thenReturn(1);
        service.reject(BOUNTY_ID, "sys:1", "自扫自核");
        verify(bountyMapper).markRejectedIfOpen(BOUNTY_ID, "sys:1", "自扫自核");
        verify(umsMemberService, never()).addBalance(anyLong(), any());

        when(bountyMapper.markRejectedIfOpen(BOUNTY_ID, "sys:1", "again")).thenReturn(0);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.reject(BOUNTY_ID, "sys:1", "again")).getCode());
    }
}
