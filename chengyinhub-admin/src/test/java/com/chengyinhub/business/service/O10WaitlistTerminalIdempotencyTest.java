package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.ClubWaitlistServiceImpl;
import com.chengyinhub.business.service.impl.ClubWaitlistTokenCodec;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * Doc2 O-10:候补终态可回读 + 幂等(重复晋级、取消竞态)。
 *
 * 真实并发由 series -> club -> ticket -> waitlist 的 FOR UPDATE 锁序串行化;这里按「后到者看到的
 * 当前读」构造每条竞态的输家视角,断言它不重复占/还库存、不重复发候补通知,且终态能从 myStatus 读回。
 */
class O10WaitlistTerminalIdempotencyTest {
    private static final Long MEMBER = 41L;
    private static final Long CLUB = 21L;
    private static final Long ACTIVITY = 31L;
    private static final Long TICKET = 51L;
    private static final Date NOW = new Date(1_800_000_000_000L);

    private ClubEventOpsMapper mapper;
    private OmsTicketMapper ticketMapper;
    private ClubWaitlistServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubEventOpsMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        service = new ClubWaitlistServiceImpl(mapper, ticketMapper, mock(IClubParticipationGuard.class),
                new ClubWaitlistTokenCodec("o10-waitlist-secret"));
        when(mapper.selectSeriesPolicyByActivityForUpdate(ACTIVITY)).thenReturn(policy());
        when(mapper.selectSeriesPolicyByActivity(ACTIVITY)).thenReturn(policy());
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(policy());
        when(mapper.isActiveParticipant(CLUB, MEMBER)).thenReturn(1);
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(999L);
        when(mapper.selectPromotableActivityForUpdate(eq(ACTIVITY), eq(CLUB), any(Date.class))).thenReturn(ACTIVITY);
        when(mapper.selectActiveClubMemberForUpdate(CLUB, MEMBER)).thenReturn(MEMBER);
        when(mapper.insertCampaign(any(ClubEventCampaign.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ClubEventCampaign.class).setId(71L);
            return 1;
        });
        when(mapper.insertDelivery(any(ClubEventDelivery.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ClubEventDelivery.class).setId(81L);
            return 1;
        });
        when(mapper.insertOutbox(anyLong(), any(Date.class))).thenReturn(1);
    }

    @Test
    void duplicatePromoteAfterSeatWasOfferedDoesNotHoldOrNotifyTwice() {
        // 第一次晋级拿走唯一名额;第二次(重复调度/并发维护)当前读到库存 0。
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(1L), ticket(0L));
        when(mapper.selectFirstWaitingForUpdate(ACTIVITY, TICKET))
                .thenReturn(entry(61L, ClubWaitlistEntry.WAITING, 0, null));
        when(ticketMapper.deductInventory(TICKET, 1L)).thenReturn(1);
        when(mapper.offerWaitingCas(eq(61L), eq(0), anyString(), eq(NOW), any())).thenReturn(1);

        assertTrue(service.promoteNext(ACTIVITY, TICKET, NOW));
        assertFalse(service.promoteNext(ACTIVITY, TICKET, NOW));

        verify(ticketMapper, times(1)).deductInventory(TICKET, 1L);
        verify(mapper, times(1)).offerWaitingCas(anyLong(), anyInt(), anyString(), any(), any());
        verify(mapper, times(1)).insertCampaign(any(ClubEventCampaign.class));
        verify(mapper, times(1)).insertDelivery(any(ClubEventDelivery.class));
        verify(mapper, times(1)).insertOutbox(81L, NOW);
    }

    @Test
    void duplicatePromoteWithSpareSeatButNoWaitingHeadIsNoOp() {
        // 队首已被上一轮转成 OFFERED,队列再无 WAITING:不能凭空占库存或再发通知。
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(1L));
        when(mapper.selectFirstWaitingForUpdate(ACTIVITY, TICKET)).thenReturn(null);

        assertFalse(service.promoteNext(ACTIVITY, TICKET, NOW));

        verify(ticketMapper, never()).deductInventory(anyLong(), anyLong());
        verify(mapper, never()).offerWaitingCas(anyLong(), anyInt(), anyString(), any(), any());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
    }

    @Test
    void cancelAfterOfferWasClaimedIsConflictAndNeverRestoresInventory() {
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(0L));
        when(mapper.selectActiveWaitlistForUpdate(MEMBER, ACTIVITY, TICKET))
                .thenReturn(entry(61L, ClubWaitlistEntry.CLAIMED, 2, new Date(NOW.getTime() + 60_000L)));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.cancel(MEMBER, ACTIVITY, TICKET, NOW));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        verify(mapper, never()).cancelActiveWaitlistCas(anyLong(), anyInt(), anyString(),
                anyLong(), anyLong(), anyLong());
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
    }

    @Test
    void cancelLosingCasRaceFailsClosedWithoutRestoreOrPromotion() {
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(0L));
        when(mapper.selectActiveWaitlistForUpdate(MEMBER, ACTIVITY, TICKET))
                .thenReturn(entry(61L, ClubWaitlistEntry.OFFERED, 1, new Date(NOW.getTime() + 60_000L)));
        when(mapper.cancelActiveWaitlistCas(61L, 1, ClubWaitlistEntry.OFFERED, MEMBER, ACTIVITY, TICKET))
                .thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.cancel(MEMBER, ACTIVITY, TICKET, NOW));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
        verify(mapper, never()).selectFirstWaitingForUpdate(anyLong(), anyLong());
    }

    @Test
    void cancelAfterExpiryWonTheRaceIsNoOpAndStatusReadsBackExpired() {
        // 过期任务先提交:取消方的当前读里已无活跃候补,只能如实返回 false,不能再还一次库存。
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(1L));
        when(mapper.selectActiveWaitlistForUpdate(MEMBER, ACTIVITY, TICKET)).thenReturn(null);
        when(mapper.selectLatestWaitlist(MEMBER, ACTIVITY, TICKET))
                .thenReturn(entry(61L, ClubWaitlistEntry.EXPIRED, 2, NOW));

        assertFalse(service.cancel(MEMBER, ACTIVITY, TICKET, NOW));
        ClubWaitlistEntry status = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.EXPIRED, status.getState());
        assertEquals(Long.valueOf(61L), status.getId());
        assertNull(status.getOfferToken());
        assertTrue(status.getWaitlistJoinAllowed());
        verify(mapper, never()).cancelActiveWaitlistCas(anyLong(), anyInt(), anyString(),
                anyLong(), anyLong(), anyLong());
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
    }

    @Test
    void expiryAfterCancelWonTheRaceIsNoOpWithoutSecondRestoreOrNotification() {
        Date expiredAt = new Date(NOW.getTime() - 1_000L);
        when(mapper.selectWaitlistById(61L)).thenReturn(entry(61L, ClubWaitlistEntry.OFFERED, 1, expiredAt));
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(1L));
        when(mapper.selectWaitlistByIdForUpdate(61L))
                .thenReturn(entry(61L, ClubWaitlistEntry.CANCELLED, 2, expiredAt));

        assertFalse(service.expireOffered(61L, NOW));

        verify(mapper, never()).expireOfferedCas(anyLong(), anyInt(), any());
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
    }

    @Test
    void cancelledWaitingEntryReadsBackAsCancelledAndSecondCancelIsNoOp() {
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(0L));
        when(mapper.selectActiveWaitlistForUpdate(MEMBER, ACTIVITY, TICKET))
                .thenReturn(entry(61L, ClubWaitlistEntry.WAITING, 0, null), (ClubWaitlistEntry) null);
        when(mapper.cancelActiveWaitlistCas(61L, 0, ClubWaitlistEntry.WAITING, MEMBER, ACTIVITY, TICKET))
                .thenReturn(1);
        when(mapper.selectLatestWaitlist(MEMBER, ACTIVITY, TICKET))
                .thenReturn(entry(61L, ClubWaitlistEntry.CANCELLED, 1, null));

        assertTrue(service.cancel(MEMBER, ACTIVITY, TICKET, NOW));
        assertFalse(service.cancel(MEMBER, ACTIVITY, TICKET, NOW));
        ClubWaitlistEntry status = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.CANCELLED, status.getState());
        assertEquals(ClubWaitlistEntry.ELIGIBILITY_ELIGIBLE, status.getEligibilityState());
        assertTrue(status.getWaitlistJoinAllowed());
        assertNull(status.getOfferToken());
        verify(mapper, times(1)).cancelActiveWaitlistCas(anyLong(), anyInt(), anyString(),
                anyLong(), anyLong(), anyLong());
        // WAITING 从未占库存:取消不应回补。
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
    }

    @Test
    void anotherMembersTerminalEntryIsNeverReadBackAsMine() {
        ClubWaitlistEntry foreign = entry(61L, ClubWaitlistEntry.CANCELLED, 1, null);
        foreign.setMemberId(42L);
        when(mapper.selectLatestWaitlist(MEMBER, ACTIVITY, TICKET)).thenReturn(foreign);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.myStatus(MEMBER, ACTIVITY, TICKET));

        assertEquals(Integer.valueOf(HttpStatus.FORBIDDEN), error.getCode());
    }

    private ClubEventSeries policy() {
        ClubEventSeries policy = new ClubEventSeries();
        policy.setId(11L);
        policy.setClubId(CLUB);
        policy.setWaitlistEnabled(true);
        policy.setOfferMinutes(20);
        policy.setStatus(ClubEventSeries.STATUS_ACTIVE);
        return policy;
    }

    private OmsTicket ticket(long remaining) {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET);
        ticket.setOwnerType(2L);
        ticket.setOwnerId(ACTIVITY);
        ticket.setRemainingInventory(remaining);
        ticket.setTotalInventory(10L);
        ticket.setDelFlag(0);
        return ticket;
    }

    private ClubWaitlistEntry entry(Long id, String state, int version, Date expires) {
        ClubWaitlistEntry row = new ClubWaitlistEntry();
        row.setId(id);
        row.setClubId(CLUB);
        row.setActivityId(ACTIVITY);
        row.setTicketId(TICKET);
        row.setMemberId(MEMBER);
        row.setState(state);
        row.setVersion(version);
        row.setOfferExpiresAt(expires);
        return row;
    }
}
