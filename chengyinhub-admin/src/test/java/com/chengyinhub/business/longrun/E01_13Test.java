package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.impl.ClubWaitlistServiceImpl;
import com.chengyinhub.business.service.impl.ClubWaitlistTokenCodec;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Service boundary tests with mocked persistence, not MySQL or transaction proof. */
class E01_13Test {
    private ClubEventOpsMapper mapper;
    private OmsTicketMapper tickets;
    private ClubWaitlistServiceImpl service;
    private OmsTicket ticket;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubEventOpsMapper.class);
        tickets = mock(OmsTicketMapper.class);
        service = new ClubWaitlistServiceImpl(mapper, tickets, mock(IClubParticipationGuard.class),
                new ClubWaitlistTokenCodec("e01-13-test-secret"));
        ClubEventSeries policy = new ClubEventSeries();
        policy.setId(1L); policy.setClubId(2L); policy.setStatus(ClubEventSeries.STATUS_ACTIVE);
        policy.setWaitlistEnabled(true);
        when(mapper.selectSeriesPolicyByActivityForUpdate(3L)).thenReturn(policy);
        when(mapper.isActiveParticipant(2L, 5L)).thenReturn(1);
        when(mapper.selectPromotableActivityForUpdate(eq(3L), eq(2L), any(Date.class))).thenReturn(3L);
        ticket = new OmsTicket();
        ticket.setId(4L); ticket.setOwnerType(2L); ticket.setOwnerId(3L); ticket.setDelFlag(0);
        ticket.setTotalInventory(1L); ticket.setRemainingInventory(0L);
        when(tickets.selectOmsTicketByIdForUpdate(4L)).thenReturn(ticket);
        when(mapper.insertWaitlist(any())).thenAnswer(call -> {
            ClubWaitlistEntry entry = call.getArgument(0); entry.setId(6L); return 1;
        });
    }

    @Test
    void eligibleSoldOutJoinUsesSameInstantForActivityCheckAndJoinedAt() {
        ClubWaitlistEntry joined = service.join(5L, 3L, 4L);
        ArgumentCaptor<Date> now = ArgumentCaptor.forClass(Date.class);
        verify(mapper).selectPromotableActivityForUpdate(eq(3L), eq(2L), now.capture());
        assertSame(now.getValue(), joined.getJoinedAt());
        assertEquals(ClubWaitlistEntry.WAITING, joined.getState());
        org.mockito.InOrder order = inOrder(tickets, mapper);
        order.verify(tickets).selectOmsTicketByIdForUpdate(4L);
        order.verify(mapper).selectPromotableActivityForUpdate(eq(3L), eq(2L), any(Date.class));
        verify(tickets, never()).deductInventory(anyLong(), anyLong());
        verify(tickets, never()).restoreInventory(anyLong(), anyLong());
    }

    @Test
    void unavailableActivityOrWrongScopeFailsClosedWithoutInsertingOrCleaningExistingEntries() {
        for (Long result : new Long[]{null, 0L, 99L}) {
            when(mapper.selectPromotableActivityForUpdate(eq(3L), eq(2L), any(Date.class))).thenReturn(result);
            assertThrows(ServiceException.class, () -> service.join(5L, 3L, 4L));
        }
        verify(mapper, never()).insertWaitlist(any());
        // No further mapper operation can terminate or cancel a pre-existing WAITING entry.
        verify(mapper, times(3)).selectSeriesPolicyByActivityForUpdate(3L);
        verify(mapper, times(3)).isActiveParticipant(2L, 5L);
        verify(mapper, times(3)).selectPromotableActivityForUpdate(eq(3L), eq(2L), any(Date.class));
        verifyNoMoreInteractions(mapper);
        verify(tickets, times(3)).selectOmsTicketByIdForUpdate(4L);
        verifyNoMoreInteractions(tickets);
    }

    @Test
    void signupDeadlineEqualToOrBeforeCheckInstantRejectsWithoutWrites() {
        for (long offset : new long[]{0, -1}) {
            when(mapper.selectPromotableActivityForUpdate(eq(3L), eq(2L), any(Date.class))).thenAnswer(call -> {
                Date now = call.getArgument(2);
                ticket.setSignupDeadline(new Date(now.getTime() + offset));
                return 3L;
            });
            assertThrows(ServiceException.class, () -> service.join(5L, 3L, 4L));
        }
        verify(mapper, never()).insertWaitlist(any());
        verify(tickets, never()).deductInventory(anyLong(), anyLong());
        verify(tickets, never()).restoreInventory(anyLong(), anyLong());
    }

    @Test
    void explicitFutureSignupDeadlineTakesPriorityOverPastStartTimeAndSaleDisplayFields() {
        ticket.setStartTime(new Date(0));
        ticket.setSaleStartTime(new Date(Long.MAX_VALUE));
        ticket.setSaleEndTime(new Date(0));
        when(mapper.selectPromotableActivityForUpdate(eq(3L), eq(2L), any(Date.class))).thenAnswer(call -> {
            Date now = call.getArgument(2); ticket.setSignupDeadline(new Date(now.getTime() + 60000)); return 3L;
        });
        assertEquals(ClubWaitlistEntry.WAITING, service.join(5L, 3L, 4L).getState());
    }

    @Test
    void missingSignupDeadlineFallsBackToStartTimeIncludingExactCutoff() {
        when(mapper.selectPromotableActivityForUpdate(eq(3L), eq(2L), any(Date.class))).thenAnswer(call -> {
            Date now = call.getArgument(2); ticket.setStartTime(now); return 3L;
        });
        assertThrows(ServiceException.class, () -> service.join(5L, 3L, 4L));
        verify(mapper, never()).insertWaitlist(any());
        when(mapper.selectPromotableActivityForUpdate(eq(3L), eq(2L), any(Date.class))).thenAnswer(call -> {
            Date now = call.getArgument(2); ticket.setStartTime(new Date(now.getTime() + 60000)); return 3L;
        });
        assertEquals(ClubWaitlistEntry.WAITING, service.join(5L, 3L, 4L).getState());
    }

    @Test
    void existingWaitingRemainsUnmodifiedAndDuplicateJoinStillRejects() {
        ClubWaitlistEntry existing = new ClubWaitlistEntry();
        existing.setId(7L); existing.setState(ClubWaitlistEntry.WAITING); existing.setVersion(4);
        when(mapper.selectActiveWaitlist(5L, 3L, 4L)).thenReturn(existing);
        assertThrows(ServiceException.class, () -> service.join(5L, 3L, 4L));
        assertEquals(ClubWaitlistEntry.WAITING, existing.getState());
        assertEquals(Integer.valueOf(4), existing.getVersion());
        verify(mapper, never()).insertWaitlist(any());
        verify(tickets, never()).deductInventory(anyLong(), anyLong());
        verify(tickets, never()).restoreInventory(anyLong(), anyLong());
    }
}
