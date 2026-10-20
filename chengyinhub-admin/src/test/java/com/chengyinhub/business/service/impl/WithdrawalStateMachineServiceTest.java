package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import com.chengyinhub.business.domain.dto.WithdrawalReviewDTO;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberWithdrawalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WithdrawalStateMachineServiceTest {

    @Test
    void approvePendingWithdrawalDoesNotRefundBalance() {
        UmsMemberWithdrawalMapper withdrawalMapper = mock(UmsMemberWithdrawalMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        UmsMemberBalanceDetailMapper detailMapper = mock(UmsMemberBalanceDetailMapper.class);
        UmsMemberWithdrawalServiceImpl service = service(withdrawalMapper, memberMapper, detailMapper);
        UmsMemberWithdrawal withdrawal = withdrawal(100L, 5L, new BigDecimal("20.00"));
        when(withdrawalMapper.selectUmsMemberWithdrawalById(100L)).thenReturn(withdrawal);
        when(withdrawalMapper.updateWithdrawalStatusIfPending(eq(100L), eq(1L), eq(7L), any(), eq("ok"))).thenReturn(1);
        WithdrawalReviewDTO request = review(100L, 1L, "ok");

        int rows = service.reviewWithdrawal(request, 7L);

        assertEquals(1, rows);
        verify(memberMapper, never()).addBalance(any(), any());
        verify(detailMapper, never()).insertUmsMemberBalanceDetail(any());
    }

    @Test
    void rejectedPendingWithdrawalRefundsOnceAfterConditionalStatusUpdate() {
        UmsMemberWithdrawalMapper withdrawalMapper = mock(UmsMemberWithdrawalMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        UmsMemberBalanceDetailMapper detailMapper = mock(UmsMemberBalanceDetailMapper.class);
        UmsMemberWithdrawalServiceImpl service = service(withdrawalMapper, memberMapper, detailMapper);
        UmsMemberWithdrawal withdrawal = withdrawal(100L, 5L, new BigDecimal("20.00"));
        UmsMember member = new UmsMember();
        member.setId(5L);
        member.setBalance(new BigDecimal("80.00"));
        when(withdrawalMapper.selectUmsMemberWithdrawalById(100L)).thenReturn(withdrawal);
        when(withdrawalMapper.updateWithdrawalStatusIfPending(eq(100L), eq(2L), eq(7L), any(), eq("bad account"))).thenReturn(1);
        when(memberMapper.selectUmsMemberById(5L)).thenReturn(member);
        when(memberMapper.addBalance(5L, new BigDecimal("20.00"))).thenReturn(1);
        when(detailMapper.insertUmsMemberBalanceDetail(any(UmsMemberBalanceDetail.class))).thenReturn(1);
        WithdrawalReviewDTO request = review(100L, 2L, "bad account");

        int rows = service.reviewWithdrawal(request, 7L);

        assertEquals(1, rows);
        verify(memberMapper).addBalance(5L, new BigDecimal("20.00"));
        verify(detailMapper).insertUmsMemberBalanceDetail(org.mockito.ArgumentMatchers.argThat(detail ->
                Long.valueOf(4L).equals(detail.getEventType())
                        && Long.valueOf(1L).equals(detail.getChangeType())
                        && new BigDecimal("100.00").compareTo(detail.getAfterBalance()) == 0));
    }

    @Test
    void repeatedRejectionDoesNotRefundWhenConditionalUpdateFails() {
        UmsMemberWithdrawalMapper withdrawalMapper = mock(UmsMemberWithdrawalMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        UmsMemberBalanceDetailMapper detailMapper = mock(UmsMemberBalanceDetailMapper.class);
        UmsMemberWithdrawalServiceImpl service = service(withdrawalMapper, memberMapper, detailMapper);
        when(withdrawalMapper.selectUmsMemberWithdrawalById(100L))
                .thenReturn(withdrawal(100L, 5L, new BigDecimal("20.00")));
        when(withdrawalMapper.updateWithdrawalStatusIfPending(eq(100L), eq(2L), eq(7L), any(), eq("again"))).thenReturn(0);

        int rows = service.reviewWithdrawal(review(100L, 2L, "again"), 7L);

        assertEquals(0, rows);
        verify(memberMapper, never()).addBalance(any(), any());
        verify(detailMapper, never()).insertUmsMemberBalanceDetail(any());
    }

    @Test
    void reviewRejectsInvalidStatus() {
        UmsMemberWithdrawalServiceImpl service = new UmsMemberWithdrawalServiceImpl();
        assertThrows(IllegalArgumentException.class, () -> service.reviewWithdrawal(review(100L, 3L, "bad"), 7L));
    }

    private UmsMemberWithdrawalServiceImpl service(UmsMemberWithdrawalMapper withdrawalMapper,
                                                   UmsMemberMapper memberMapper,
                                                   UmsMemberBalanceDetailMapper detailMapper) {
        UmsMemberWithdrawalServiceImpl service = new UmsMemberWithdrawalServiceImpl();
        ReflectionTestUtils.setField(service, "umsMemberWithdrawalMapper", withdrawalMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        org.mockito.Mockito.doAnswer(i -> {
            UmsMember member = memberMapper.selectUmsMemberById(i.getArgument(0));
            return member == null ? null : (member.getBalance() == null ? BigDecimal.ZERO : member.getBalance());
        }).when(memberMapper).selectBalanceForUpdate(org.mockito.ArgumentMatchers.anyLong());
        ReflectionTestUtils.setField(service, "umsMemberBalanceDetailMapper", detailMapper);
        return service;
    }

    private WithdrawalReviewDTO review(Long id, Long status, String reason) {
        WithdrawalReviewDTO request = new WithdrawalReviewDTO();
        request.setId(id);
        request.setStatus(status);
        request.setReviewReason(reason);
        return request;
    }

    private UmsMemberWithdrawal withdrawal(Long id, Long memberId, BigDecimal amount) {
        UmsMemberWithdrawal withdrawal = new UmsMemberWithdrawal();
        withdrawal.setId(id);
        withdrawal.setMemberId(memberId);
        withdrawal.setWithdrawalAmount(amount);
        withdrawal.setReceivedAmount(amount);
        withdrawal.setStatus(0L);
        return withdrawal;
    }
}
