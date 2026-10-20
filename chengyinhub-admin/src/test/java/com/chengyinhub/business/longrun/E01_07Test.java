package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.impl.ClubWaitlistServiceImpl;
import com.chengyinhub.business.service.impl.ClubWaitlistTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** NOT_RUN. Deterministic stale/current mapper answers; this does not claim a real MySQL execution. */
class E01_07Test {
    private static final Long MEMBER = 41L, CLUB = 21L, ACTIVITY = 31L, TICKET = 51L, OFFER = 61L;
    private ClubEventOpsMapper mapper;
    private OmsTicketMapper tickets;
    private ClubWaitlistServiceImpl service;
    private ClubEventSeries policy;
    private Date expiredAt;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubEventOpsMapper.class);
        tickets = mock(OmsTicketMapper.class);
        service = new ClubWaitlistServiceImpl(mapper, tickets, mock(IClubParticipationGuard.class),
                new ClubWaitlistTokenCodec("e01-07-regression-secret"));
        policy = new ClubEventSeries();
        policy.setId(11L);
        policy.setClubId(CLUB);
        policy.setStatus(ClubEventSeries.STATUS_ACTIVE);
        policy.setWaitlistEnabled(true);
        when(mapper.selectSeriesPolicyByActivity(ACTIVITY)).thenReturn(policy);
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(policy);
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(MEMBER);
        when(mapper.isActiveParticipant(CLUB, MEMBER)).thenReturn(1);
        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET);
        ticket.setOwnerType(2L);
        ticket.setOwnerId(ACTIVITY);
        ticket.setTotalInventory(1L);
        ticket.setRemainingInventory(0L);
        ticket.setDelFlag(0);
        when(tickets.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket);
        expiredAt = new Date(System.currentTimeMillis() - 60_000L);
        // Every ordinary read repeats the same old OFFERED image, as an established RR snapshot can.
        when(mapper.selectLatestWaitlist(MEMBER, ACTIVITY, TICKET))
                .thenAnswer(invocation -> entry(ClubWaitlistEntry.OFFERED, 1));
        when(mapper.selectWaitlistById(OFFER))
                .thenAnswer(invocation -> entry(ClubWaitlistEntry.OFFERED, 1));
        // master 过期回补后同事务投递站内通知；夹具让通知三张表各写一行成功。
        when(mapper.insertCampaign(any())).thenAnswer(invocation -> {
            ((com.chengyinhub.business.domain.ClubEventCampaign) invocation.getArgument(0)).setId(81L);
            return 1;
        });
        when(mapper.insertDelivery(any())).thenAnswer(invocation -> {
            ((com.chengyinhub.business.domain.ClubEventDelivery) invocation.getArgument(0)).setId(82L);
            return 1;
        });
        when(mapper.insertOutbox(anyLong(), any())).thenReturn(1);
    }

    @Test
    void claimedBeforeExpiryLockRemainsClaimedWithRegistrationAndNoPayToken() {
        ClubWaitlistEntry claimed = entry(ClubWaitlistEntry.CLAIMED, 2);
        claimed.setRegistrationId(71L);
        when(mapper.selectWaitlistByIdForUpdate(OFFER)).thenReturn(claimed);
        when(mapper.countActiveRegistration(MEMBER, ACTIVITY, TICKET)).thenReturn(1);

        ClubWaitlistEntry status = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.CLAIMED, status.getState());
        assertEquals(Long.valueOf(71L), status.getRegistrationId());
        assertEquals(Integer.valueOf(2), status.getVersion());
        assertEquals(ClubWaitlistEntry.ELIGIBILITY_REGISTERED, status.getEligibilityState());
        assertFalse(status.getWaitlistJoinAllowed());
        assertNull(status.getOfferToken());
        verify(mapper, times(1)).selectLatestWaitlist(MEMBER, ACTIVITY, TICKET);
        verify(mapper, times(2)).selectWaitlistByIdForUpdate(OFFER);
        InOrder locks = inOrder(mapper, tickets);
        locks.verify(mapper).selectSeriesByActivityAnyStatusForUpdate(ACTIVITY);
        locks.verify(mapper).selectClubOwnerForUpdate(CLUB);
        locks.verify(tickets).selectOmsTicketByIdForUpdate(TICKET);
        locks.verify(mapper, times(2)).selectWaitlistByIdForUpdate(OFFER);
        verify(mapper, never()).expireOfferedCas(anyLong(), anyInt(), any());
        verify(tickets, never()).restoreInventory(anyLong(), anyLong());
    }

    @Test
    void concurrentConversionRemainsConvertedEvenWhenWaitlistPolicyIsClosed() {
        policy.setWaitlistEnabled(false);
        ClubWaitlistEntry converted = entry(ClubWaitlistEntry.CONVERTED, 3);
        converted.setRegistrationId(71L);
        when(mapper.selectWaitlistByIdForUpdate(OFFER)).thenReturn(converted);

        ClubWaitlistEntry status = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.CONVERTED, status.getState());
        assertEquals(Long.valueOf(71L), status.getRegistrationId());
        assertEquals(ClubWaitlistEntry.ELIGIBILITY_CLOSED, status.getEligibilityState());
        assertFalse(status.getWaitlistJoinAllowed());
        assertNull(status.getOfferToken());
        verify(mapper, times(1)).selectLatestWaitlist(MEMBER, ACTIVITY, TICKET);
        verify(tickets, never()).restoreInventory(anyLong(), anyLong());
    }

    @Test
    void successfulExpiryStillRestoresOneHoldAndReadsPersistedExpiredVersion() {
        ClubWaitlistEntry offered = entry(ClubWaitlistEntry.OFFERED, 1);
        ClubWaitlistEntry expired = entry(ClubWaitlistEntry.EXPIRED, 2);
        when(mapper.selectWaitlistByIdForUpdate(OFFER)).thenReturn(offered, expired);
        when(mapper.expireOfferedCas(eq(OFFER), eq(1), any())).thenReturn(1);
        when(tickets.restoreInventory(TICKET, 1L)).thenReturn(1);

        ClubWaitlistEntry status = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.EXPIRED, status.getState());
        assertEquals(Integer.valueOf(2), status.getVersion());
        assertNull(status.getOfferToken());
        verify(mapper, times(1)).expireOfferedCas(eq(OFFER), eq(1), any());
        verify(tickets, times(1)).restoreInventory(TICKET, 1L);
        verify(mapper, times(1)).selectLatestWaitlist(MEMBER, ACTIVITY, TICKET);
    }

    private ClubWaitlistEntry entry(String state, int version) {
        ClubWaitlistEntry entry = new ClubWaitlistEntry();
        entry.setId(OFFER);
        entry.setClubId(CLUB);
        entry.setMemberId(MEMBER);
        entry.setActivityId(ACTIVITY);
        entry.setTicketId(TICKET);
        entry.setState(state);
        entry.setVersion(version);
        entry.setOfferExpiresAt(expiredAt);
        return entry;
    }
}
