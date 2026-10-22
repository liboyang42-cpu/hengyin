package com.chengyinhub.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistClaim;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.ClubWaitlistServiceImpl;
import com.chengyinhub.business.service.impl.ClubWaitlistTokenCodec;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Date;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ClubWaitlistServiceImplTest {
    private static final Long MEMBER = 41L;
    private static final Long CLUB = 21L;
    private static final Long ACTIVITY = 31L;
    private static final Long TICKET = 51L;
    private static final Date NOW = new Date(1_800_000_000_000L);

    private ClubEventOpsMapper mapper;
    private OmsTicketMapper ticketMapper;
    private IClubParticipationGuard participationGuard;
    private ClubWaitlistTokenCodec tokenCodec;
    private ClubWaitlistServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubEventOpsMapper.class);
        when(mapper.selectWaitlistMaintenanceMaxId()).thenReturn(100L);
        ticketMapper = mock(OmsTicketMapper.class);
        participationGuard = mock(IClubParticipationGuard.class);
        tokenCodec = new ClubWaitlistTokenCodec("waitlist-test-secret");
        service = new ClubWaitlistServiceImpl(mapper, ticketMapper, participationGuard, tokenCodec);
        when(mapper.selectSeriesPolicyByActivityForUpdate(ACTIVITY)).thenReturn(policy(true));
        when(mapper.selectSeriesPolicyByActivity(ACTIVITY)).thenReturn(policy(true));
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(policy(true));
        when(mapper.isActiveParticipant(CLUB, MEMBER)).thenReturn(1);
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(999L);
        when(mapper.selectPromotableActivityForUpdate(eq(ACTIVITY), eq(CLUB), any(Date.class))).thenReturn(ACTIVITY);
        when(mapper.selectActiveClubMemberForUpdate(CLUB, MEMBER)).thenReturn(MEMBER);
        when(mapper.countActiveRegistration(MEMBER, ACTIVITY, TICKET)).thenReturn(0);
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

    private java.util.List<Map<String, Object>> maintenanceTarget(long scanId, long targetId) {
        Map<String, Object> row = new HashMap<>(); row.put("scanId", scanId); row.put("targetId", targetId);
        return Collections.singletonList(row);
    }

    @Test
    void fullActivityAllowsOneActiveFifoWaitlistEntry() {
        OmsTicket soldOut = ticket(0L);
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(soldOut);
        when(mapper.insertWaitlist(any())).thenAnswer(invocation -> {
            ClubWaitlistEntry row = invocation.getArgument(0);
            row.setId(61L);
            return 1;
        });

        ClubWaitlistEntry joined = service.join(MEMBER, ACTIVITY, TICKET);

        assertEquals(Long.valueOf(61L), joined.getId());
        assertEquals(ClubWaitlistEntry.WAITING, joined.getState());
        verify(participationGuard).requireAllowed(CLUB, MEMBER);
        verify(mapper).countActiveRegistration(MEMBER, ACTIVITY, TICKET);
    }

    @Test
    void promotionLocksClubBeforeTicketThenDeductsOneBeforeOfferingFifoHead() {
        OmsTicket available = ticket(1L);
        ClubWaitlistEntry waiting = entry(61L, ClubWaitlistEntry.WAITING, 0, null);
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(available);
        when(mapper.selectFirstWaitingForUpdate(ACTIVITY, TICKET)).thenReturn(waiting);
        when(ticketMapper.deductInventory(TICKET, 1L)).thenReturn(1);
        when(mapper.offerWaitingCas(eq(61L), eq(0), any(), eq(NOW), any())).thenReturn(1);

        assertTrue(service.promoteNext(ACTIVITY, TICKET, NOW));

        InOrder lockOrder = inOrder(mapper, ticketMapper);
        lockOrder.verify(mapper).selectSeriesPolicyByActivityForUpdate(ACTIVITY);
        lockOrder.verify(mapper).selectClubOwnerForUpdate(CLUB);
        lockOrder.verify(ticketMapper).selectOmsTicketByIdForUpdate(TICKET);
        verify(ticketMapper).selectOmsTicketByIdForUpdate(TICKET);
        verify(ticketMapper).deductInventory(TICKET, 1L);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(mapper).offerWaitingCas(eq(61L), eq(0), hash.capture(), eq(NOW), any());
        assertEquals(64, hash.getValue().length());

        ArgumentCaptor<ClubEventCampaign> campaign = ArgumentCaptor.forClass(ClubEventCampaign.class);
        verify(mapper).insertCampaign(campaign.capture());
        assertEquals(CLUB, campaign.getValue().getClubId());
        assertEquals(ACTIVITY, campaign.getValue().getActivityId());
        assertEquals("WAITLIST", campaign.getValue().getAudienceType());
        assertEquals("IN_APP", campaign.getValue().getChannel());
        assertEquals(Long.valueOf(999L), campaign.getValue().getActorMemberId());
        assertEquals(1, campaign.getValue().getTotalCount());
        java.util.UUID.fromString(campaign.getValue().getRequestId());
        assertEquals("你已获得活动候补名额，请在候补截止时间前完成报名。", campaign.getValue().getContent());
        org.junit.jupiter.api.Assertions.assertFalse(
                campaign.getValue().getContent().contains(tokenCodec.token(waiting)));

        ArgumentCaptor<ClubEventDelivery> delivery = ArgumentCaptor.forClass(ClubEventDelivery.class);
        verify(mapper).insertDelivery(delivery.capture());
        assertEquals(Long.valueOf(71L), delivery.getValue().getCampaignId());
        assertEquals(MEMBER, delivery.getValue().getRecipientMemberId());
        assertEquals("IN_APP", delivery.getValue().getChannel());
        assertEquals(ClubEventDelivery.PENDING, delivery.getValue().getStatus());
        verify(mapper).insertOutbox(81L, NOW);
    }

    @Test
    void promotionWithoutInventoryDoesNotCreateNotification() {
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(0L));

        org.junit.jupiter.api.Assertions.assertFalse(service.promoteNext(ACTIVITY, TICKET, NOW));

        verify(mapper, never()).selectFirstWaitingForUpdate(anyLong(), anyLong());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
        verify(mapper, never()).insertDelivery(any(ClubEventDelivery.class));
        verify(mapper, never()).insertOutbox(anyLong(), any(Date.class));
    }

    @Test
    void promotionSkipsMemberWhoLeftClubWithoutHoldingInventory() {
        OmsTicket available = ticket(1L);
        ClubWaitlistEntry stale = entry(61L, ClubWaitlistEntry.WAITING, 0, null);
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(available);
        when(mapper.selectFirstWaitingForUpdate(ACTIVITY, TICKET)).thenReturn(stale, null);
        when(mapper.isActiveParticipant(CLUB, MEMBER)).thenReturn(0);
        when(mapper.cancelActiveWaitlistCas(
                61L, 0, ClubWaitlistEntry.WAITING, MEMBER, ACTIVITY, TICKET)).thenReturn(1);
        when(ticketMapper.deductInventory(TICKET, 1L)).thenReturn(1);
        when(mapper.offerWaitingCas(eq(61L), eq(0), any(), eq(NOW), any())).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertFalse(service.promoteNext(ACTIVITY, TICKET, NOW));

        verify(mapper).cancelActiveWaitlistCas(
                61L, 0, ClubWaitlistEntry.WAITING, MEMBER, ACTIVITY, TICKET);
        verify(ticketMapper, never()).deductInventory(anyLong(), anyLong());
        verify(mapper, never()).offerWaitingCas(anyLong(), anyInt(), any(), any(), any());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
        verify(mapper, never()).insertDelivery(any(ClubEventDelivery.class));
        verify(mapper, never()).insertOutbox(anyLong(), any(Date.class));
    }

    @Test
    void validOfferClaimsSameMemberActivityAndTicketWithoutAnotherInventoryDeduction() {
        Date expires = new Date(NOW.getTime() + 20 * 60_000L);
        ClubWaitlistEntry offered = entry(61L, ClubWaitlistEntry.OFFERED, 1, expires);
        String raw = tokenCodec.token(offered);
        offered.setOfferTokenHash(tokenCodec.hash(raw));
        when(mapper.selectWaitlistByIdForUpdate(61L)).thenReturn(offered);
        when(mapper.claimOfferCas(61L, 1, MEMBER, ACTIVITY, TICKET, NOW)).thenReturn(1);
        RegistrationRequest request = request(61L, raw, ACTIVITY, TICKET);

        ClubWaitlistClaim claim = service.claimForOrder(MEMBER, request, NOW);

        assertNotNull(claim);
        assertEquals(CLUB, claim.getClubId());
        assertEquals(TICKET, claim.getTicketId());
        InOrder locks = inOrder(mapper);
        locks.verify(mapper).selectSeriesPolicyByActivityForUpdate(ACTIVITY);
        locks.verify(mapper).selectWaitlistByIdForUpdate(61L);
        verify(ticketMapper, never()).deductInventory(anyLong(), anyLong());
    }

    @Test
    void validOfferCanBeReadForQuoteWithoutClaimingOrDeductingAgain() {
        Date expires = new Date(NOW.getTime() + 20 * 60_000L);
        ClubWaitlistEntry offered = entry(61L, ClubWaitlistEntry.OFFERED, 1, expires);
        String raw = tokenCodec.token(offered);
        offered.setOfferTokenHash(tokenCodec.hash(raw));
        when(mapper.selectWaitlistById(61L)).thenReturn(offered);

        ClubWaitlistClaim hold = service.validateForQuote(
                MEMBER, request(61L, raw, ACTIVITY, TICKET), NOW);

        assertEquals(Long.valueOf(61L), hold.getOfferId());
        assertEquals(expires, hold.getExpiresAt());
        verify(mapper, never()).claimOfferCas(anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any());
        verify(ticketMapper, never()).deductInventory(anyLong(), anyLong());
    }

    @Test
    void quoteReadRejectsExpiredOrCrossTicketOfferWithoutMutation() {
        ClubWaitlistEntry offered = entry(61L, ClubWaitlistEntry.OFFERED, 1,
                new Date(NOW.getTime() - 1L));
        String raw = tokenCodec.token(offered);
        offered.setOfferTokenHash(tokenCodec.hash(raw));
        when(mapper.selectWaitlistById(61L)).thenReturn(offered);

        assertThrows(ServiceException.class, () -> service.validateForQuote(
                MEMBER, request(61L, raw, ACTIVITY, TICKET), NOW));
        offered.setOfferExpiresAt(new Date(NOW.getTime() + 60_000L));
        assertThrows(ServiceException.class, () -> service.validateForQuote(
                MEMBER, request(61L, raw, ACTIVITY, 999L), NOW));

        verify(mapper, never()).claimOfferCas(anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void crossActivityTokenFailsClosedBeforeClaimCas() {
        Date expires = new Date(NOW.getTime() + 20 * 60_000L);
        ClubWaitlistEntry offered = entry(61L, ClubWaitlistEntry.OFFERED, 1, expires);
        String raw = tokenCodec.token(offered);
        offered.setOfferTokenHash(tokenCodec.hash(raw));
        when(mapper.selectWaitlistByIdForUpdate(61L)).thenReturn(offered);

        assertThrows(ServiceException.class,
                () -> service.claimForOrder(MEMBER, request(61L, raw, 999L, TICKET), NOW));

        verify(mapper, never()).claimOfferCas(anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void offeredExpiryRestoresExactlyOnceAndSecondRunIsIdempotent() {
        Date expiredAt = new Date(NOW.getTime() - 1L);
        ClubWaitlistEntry offered = entry(61L, ClubWaitlistEntry.OFFERED, 1, expiredAt);
        ClubWaitlistEntry expired = entry(61L, ClubWaitlistEntry.EXPIRED, 2, expiredAt);
        when(mapper.selectWaitlistById(61L)).thenReturn(offered, expired);
        when(mapper.selectWaitlistByIdForUpdate(61L)).thenReturn(offered);
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(0L));
        when(mapper.expireOfferedCas(61L, 1, NOW)).thenReturn(1);
        when(ticketMapper.restoreInventory(TICKET, 1L)).thenReturn(1);

        assertTrue(service.expireOffered(61L, NOW));
        org.junit.jupiter.api.Assertions.assertFalse(service.expireOffered(61L, NOW));

        InOrder locks = inOrder(mapper, ticketMapper);
        locks.verify(mapper).selectSeriesByActivityAnyStatusForUpdate(ACTIVITY);
        locks.verify(mapper).selectClubOwnerForUpdate(CLUB);
        locks.verify(ticketMapper).selectOmsTicketByIdForUpdate(TICKET);
        locks.verify(mapper).selectWaitlistByIdForUpdate(61L);
        verify(ticketMapper, times(1)).restoreInventory(TICKET, 1L);

        ArgumentCaptor<ClubEventCampaign> campaign = ArgumentCaptor.forClass(ClubEventCampaign.class);
        verify(mapper, times(1)).insertCampaign(campaign.capture());
        assertEquals("ALL_MEMBERS", campaign.getValue().getAudienceType());
        assertEquals("候补名额已过期", campaign.getValue().getTitle());
        assertEquals("你的活动候补名额已过期，如仍需参加，请重新加入候补。", campaign.getValue().getContent());
        ArgumentCaptor<ClubEventDelivery> delivery = ArgumentCaptor.forClass(ClubEventDelivery.class);
        verify(mapper, times(1)).insertDelivery(delivery.capture());
        assertEquals(MEMBER, delivery.getValue().getRecipientMemberId());
        verify(mapper, times(1)).insertOutbox(81L, NOW);
    }

    @Test
    void closedWaitlistFailsBeforeTicketOrInventoryIsTouched() {
        when(mapper.selectSeriesPolicyByActivityForUpdate(ACTIVITY)).thenReturn(policy(false));

        assertThrows(ServiceException.class, () -> service.join(MEMBER, ACTIVITY, TICKET));

        verify(ticketMapper, never()).selectOmsTicketByIdForUpdate(anyLong());
        verify(ticketMapper, never()).deductInventory(anyLong(), anyLong());
    }

    @Test
    void concurrentPromotionCasLossFailsClosedAfterInventoryHoldInSameTransaction() {
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(1L));
        when(mapper.selectFirstWaitingForUpdate(ACTIVITY, TICKET))
                .thenReturn(entry(61L, ClubWaitlistEntry.WAITING, 0, null));
        when(ticketMapper.deductInventory(TICKET, 1L)).thenReturn(1);
        when(mapper.offerWaitingCas(eq(61L), eq(0), any(), eq(NOW), any())).thenReturn(0);

        assertThrows(ServiceException.class, () -> service.promoteNext(ACTIVITY, TICKET, NOW));

        verify(ticketMapper).deductInventory(TICKET, 1L);
        verify(mapper).offerWaitingCas(eq(61L), eq(0), any(), eq(NOW), any());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
        verify(mapper, never()).insertDelivery(any(ClubEventDelivery.class));
        verify(mapper, never()).insertOutbox(anyLong(), any(Date.class));
    }

    @Test
    void claimedTerminalOrderIsExpiredAndPromotedWithoutSecondInventoryRestore() {
        ClubWaitlistEntry claimed = entry(61L, ClubWaitlistEntry.CLAIMED, 2, NOW);
        claimed.setRegistrationId(71L);
        when(mapper.selectWaitlistMaintenancePage(2, NOW, 0L, 100L, 100)).thenReturn(maintenanceTarget(61L, 71L));
        when(mapper.selectWaitlistByRegistration(71L)).thenReturn(claimed);
        when(mapper.selectWaitlistByRegistrationForUpdate(71L)).thenReturn(claimed);
        when(mapper.expireClaimedTerminalCas(71L)).thenReturn(1);
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(0L));

        assertEquals(1, service.maintain(NOW));

        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
        verify(mapper).expireClaimedTerminalCas(71L);
        InOrder locks = inOrder(mapper, ticketMapper);
        locks.verify(mapper).selectSeriesByActivityAnyStatusForUpdate(ACTIVITY);
        locks.verify(mapper).selectClubOwnerForUpdate(CLUB);
        locks.verify(ticketMapper).selectOmsTicketByIdForUpdate(TICKET);
        locks.verify(mapper).selectWaitlistByRegistrationForUpdate(71L);
    }

    @Test
    void paidClaimMaintenanceCountsOnlyAnActualClaimedToConvertedCas() {
        when(mapper.selectWaitlistMaintenancePage(1, NOW, 0L, 100L, 100)).thenReturn(maintenanceTarget(61L, 71L));
        when(mapper.convertClaimedByRegistration(71L)).thenReturn(1);

        assertEquals(1, service.maintain(NOW));

        verify(mapper).convertClaimedByRegistration(71L);
    }

    @Test
    void paidClaimMaintenanceDoesNotReportSuccessAfterCallbackWonTheCasRace() {
        when(mapper.selectWaitlistMaintenancePage(1, NOW, 0L, 100L, 100)).thenReturn(maintenanceTarget(61L, 71L));
        when(mapper.convertClaimedByRegistration(71L)).thenReturn(0);

        assertEquals(0, service.maintain(NOW));

        verify(mapper).convertClaimedByRegistration(71L);
    }

    @Test
    void maintenancePromotesWaitingQueueAfterNormalOrderRestoredInventory() {
        Map<String, Object> scope = new HashMap<>();
        scope.put("scanId", 61L);
        scope.put("activityId", ACTIVITY);
        scope.put("ticketId", TICKET);
        when(mapper.selectWaitlistMaintenancePage(3, NOW, 0L, 100L, 100)).thenReturn(Collections.singletonList(scope));
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(1L));
        when(mapper.selectFirstWaitingForUpdate(ACTIVITY, TICKET))
                .thenReturn(entry(61L, ClubWaitlistEntry.WAITING, 0, null));
        when(ticketMapper.deductInventory(TICKET, 1L)).thenReturn(1);
        when(mapper.offerWaitingCas(eq(61L), eq(0), any(), eq(NOW), any())).thenReturn(1);

        assertEquals(1, service.maintain(NOW));

        verify(ticketMapper).deductInventory(TICKET, 1L);
        verify(mapper).offerWaitingCas(eq(61L), eq(0), any(), eq(NOW), any());
    }

    @Test
    void cancellingOfferedEntryRestoresItsHoldExactlyOnce() {
        ClubWaitlistEntry offered = entry(61L, ClubWaitlistEntry.OFFERED, 1,
                new Date(NOW.getTime() + 60_000L));
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(0L));
        when(mapper.selectActiveWaitlistForUpdate(MEMBER, ACTIVITY, TICKET)).thenReturn(offered, null);
        when(mapper.cancelActiveWaitlistCas(61L, 1, ClubWaitlistEntry.OFFERED,
                MEMBER, ACTIVITY, TICKET)).thenReturn(1);
        when(ticketMapper.restoreInventory(TICKET, 1L)).thenReturn(1);

        assertTrue(service.cancel(MEMBER, ACTIVITY, TICKET, NOW));
        org.junit.jupiter.api.Assertions.assertFalse(service.cancel(MEMBER, ACTIVITY, TICKET, NOW));

        verify(ticketMapper, times(1)).restoreInventory(TICKET, 1L);
    }

    @Test
    void statusReturnsLatestConvertedProofWithoutSerializingTokenHash() throws Exception {
        ClubWaitlistEntry converted = entry(61L, ClubWaitlistEntry.CONVERTED, 3, NOW);
        converted.setRegistrationId(71L);
        converted.setOfferTokenHash("secret-hash");
        when(mapper.selectLatestWaitlist(MEMBER, ACTIVITY, TICKET)).thenReturn(converted);

        ClubWaitlistEntry status = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.CONVERTED, status.getState());
        assertEquals(Long.valueOf(71L), status.getRegistrationId());
        String json = new ObjectMapper().writeValueAsString(status);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("offerTokenHash"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("secret-hash"));
    }

    @Test
    void statusReturnsNonMemberAsStableBusinessStateBeforeReadingQueue() {
        when(mapper.isActiveParticipant(CLUB, MEMBER)).thenReturn(0);

        ClubWaitlistEntry status = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.NONE, status.getState());
        assertEquals(ClubWaitlistEntry.ELIGIBILITY_NOT_MEMBER, status.getEligibilityState());
        org.junit.jupiter.api.Assertions.assertFalse(status.getWaitlistJoinAllowed());
        verify(mapper, never()).selectLatestWaitlist(anyLong(), anyLong(), anyLong());
    }

    @Test
    void statusReturnsNoSeriesAsStableBusinessState() {
        when(mapper.selectSeriesPolicyByActivity(ACTIVITY)).thenReturn(null);

        ClubWaitlistEntry status = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.NONE, status.getState());
        assertEquals(ClubWaitlistEntry.ELIGIBILITY_NO_SERIES, status.getEligibilityState());
        org.junit.jupiter.api.Assertions.assertFalse(status.getWaitlistJoinAllowed());
        verify(mapper, never()).isActiveParticipant(anyLong(), anyLong());
    }

    @Test
    void statusReturnsClosedAndRegisteredAsStableBusinessStates() {
        when(mapper.selectSeriesPolicyByActivity(ACTIVITY)).thenReturn(policy(false));

        ClubWaitlistEntry closed = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.ELIGIBILITY_CLOSED, closed.getEligibilityState());
        org.junit.jupiter.api.Assertions.assertFalse(closed.getWaitlistJoinAllowed());
        verify(mapper, never()).isActiveParticipant(anyLong(), anyLong());

        when(mapper.selectSeriesPolicyByActivity(ACTIVITY)).thenReturn(policy(true));
        when(mapper.countActiveRegistration(MEMBER, ACTIVITY, TICKET)).thenReturn(1);

        ClubWaitlistEntry registered = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.NONE, registered.getState());
        assertEquals(ClubWaitlistEntry.ELIGIBILITY_REGISTERED, registered.getEligibilityState());
        org.junit.jupiter.api.Assertions.assertFalse(registered.getWaitlistJoinAllowed());
    }

    @Test
    void statusReturnsExplicitEligibleEmptyState() {
        when(mapper.selectLatestWaitlist(MEMBER, ACTIVITY, TICKET)).thenReturn(null);

        ClubWaitlistEntry status = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.NONE, status.getState());
        assertEquals(ClubWaitlistEntry.ELIGIBILITY_ELIGIBLE, status.getEligibilityState());
        assertTrue(status.getWaitlistJoinAllowed());
    }

    @Test
    void statusAtomicallyExpiresStaleOfferAndNeverReturnsPayToken() {
        Date expiredAt = new Date(System.currentTimeMillis() - 60_000L);
        ClubWaitlistEntry offered = entry(61L, ClubWaitlistEntry.OFFERED, 1, expiredAt);
        String raw = tokenCodec.token(offered);
        offered.setOfferTokenHash(tokenCodec.hash(raw));
        ClubWaitlistEntry expired = entry(61L, ClubWaitlistEntry.EXPIRED, 2, expiredAt);
        when(mapper.selectLatestWaitlist(MEMBER, ACTIVITY, TICKET)).thenReturn(offered, expired);
        when(mapper.selectWaitlistById(61L)).thenReturn(offered);
        when(mapper.selectWaitlistByIdForUpdate(61L)).thenReturn(offered);
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(0L));
        when(mapper.expireOfferedCas(eq(61L), eq(1), any())).thenReturn(1);
        when(ticketMapper.restoreInventory(TICKET, 1L)).thenReturn(1);

        ClubWaitlistEntry status = service.myStatus(MEMBER, ACTIVITY, TICKET);

        assertEquals(ClubWaitlistEntry.EXPIRED, status.getState());
        assertEquals(ClubWaitlistEntry.ELIGIBILITY_ELIGIBLE, status.getEligibilityState());
        assertTrue(status.getWaitlistJoinAllowed());
        org.junit.jupiter.api.Assertions.assertNull(status.getOfferToken());
        verify(mapper).expireOfferedCas(eq(61L), eq(1), any());
        verify(ticketMapper).restoreInventory(TICKET, 1L);
    }

    @Test
    void offeredMemberWhoLeftClubCannotQuoteOrClaimCachedOffer() {
        Date expires = new Date(NOW.getTime() + 20 * 60_000L);
        ClubWaitlistEntry offered = entry(61L, ClubWaitlistEntry.OFFERED, 1, expires);
        String raw = tokenCodec.token(offered);
        offered.setOfferTokenHash(tokenCodec.hash(raw));
        when(mapper.selectWaitlistById(61L)).thenReturn(offered);
        when(mapper.selectWaitlistByIdForUpdate(61L)).thenReturn(offered);
        when(mapper.isActiveParticipant(CLUB, MEMBER)).thenReturn(0);
        RegistrationRequest request = request(61L, raw, ACTIVITY, TICKET);

        ServiceException quoteDenied = assertThrows(ServiceException.class,
                () -> service.validateForQuote(MEMBER, request, NOW));
        ServiceException claimDenied = assertThrows(ServiceException.class,
                () -> service.claimForOrder(MEMBER, request, NOW));

        assertEquals(com.chengyinhub.common.constant.HttpStatus.FORBIDDEN, quoteDenied.getCode());
        assertEquals(com.chengyinhub.common.constant.HttpStatus.FORBIDDEN, claimDenied.getCode());
        verify(mapper, never()).claimOfferCas(anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * 2026-09-11 用户裁决:认领窗口默认一天(以前是 30 分钟)。
     * 没配 offer_minutes 的系列走这个默认值。
     */
    @Test
    void offerDeadlineDefaultsToOneDayWhenSeriesHasNoConfiguredWindow() {
        ClubEventSeries noConfig = policy(true);
        noConfig.setOfferMinutes(null);
        when(mapper.selectSeriesPolicyByActivityForUpdate(ACTIVITY)).thenReturn(noConfig);
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(ticket(1L));
        when(mapper.selectFirstWaitingForUpdate(ACTIVITY, TICKET))
                .thenReturn(entry(61L, ClubWaitlistEntry.WAITING, 0, null));
        when(ticketMapper.deductInventory(TICKET, 1L)).thenReturn(1);
        when(mapper.offerWaitingCas(eq(61L), eq(0), any(), eq(NOW), any())).thenReturn(1);

        assertTrue(service.promoteNext(ACTIVITY, TICKET, NOW));

        ArgumentCaptor<Date> deadline = ArgumentCaptor.forClass(Date.class);
        verify(mapper).offerWaitingCas(eq(61L), eq(0), any(), eq(NOW), deadline.capture());
        assertEquals(NOW.getTime() + 1440L * 60_000L, deadline.getValue().getTime(),
                "没配 offer_minutes 时的默认窗口必须是一天");
    }

    /**
     * ★窗口拉长到一天之后,这条才是真护栏:保留期不能活过这张票自己的报名截止,
     * 否则名额会被锁到活动开场之后,队列里所有人一起陪葬。
     */
    @Test
    void offerDeadlineNeverOutlivesTheTicketSaleDeadline() {
        Date saleEnd = new Date(NOW.getTime() + 10L * 60_000L);
        OmsTicket closingSoon = ticket(1L);
        closingSoon.setSignupDeadline(saleEnd);
        when(ticketMapper.selectOmsTicketByIdForUpdate(TICKET)).thenReturn(closingSoon);
        when(mapper.selectFirstWaitingForUpdate(ACTIVITY, TICKET))
                .thenReturn(entry(62L, ClubWaitlistEntry.WAITING, 0, null));
        when(ticketMapper.deductInventory(TICKET, 1L)).thenReturn(1);
        when(mapper.offerWaitingCas(eq(62L), eq(0), any(), eq(NOW), any())).thenReturn(1);

        assertTrue(service.promoteNext(ACTIVITY, TICKET, NOW));

        ArgumentCaptor<Date> clamped = ArgumentCaptor.forClass(Date.class);
        verify(mapper).offerWaitingCas(eq(62L), eq(0), any(), eq(NOW), clamped.capture());
        assertEquals(saleEnd.getTime(), clamped.getValue().getTime(),
                "★保留期不能活过这张票的报名截止");
    }

    private ClubEventSeries policy(boolean enabled) {
        ClubEventSeries policy = new ClubEventSeries();
        policy.setId(11L);
        policy.setClubId(CLUB);
        policy.setWaitlistEnabled(enabled);
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

    private RegistrationRequest request(Long offerId, String token, Long activityId, Long ticketId) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(activityId);
        request.setTicketId(ticketId);
        request.setWaitlistOfferId(offerId);
        request.setWaitlistOfferToken(token);
        return request;
    }

    /** 拍板 2026-10-22:商家可以是俱乐部成员,但不能报名(含候补)俱乐部活动。 */
    @Test
    void merchantMemberCannotJoinClubActivityWaitlist() {
        ICapabilityService capabilityService = mock(ICapabilityService.class);
        when(capabilityService.hasCapability(MEMBER, com.chengyinhub.business.domain.MemberCapability.CAP_MERCHANT))
                .thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "capabilityService", capabilityService);
        ServiceException refused = assertThrows(ServiceException.class, () -> service.join(MEMBER, ACTIVITY, TICKET));
        assertEquals("商家不能报名俱乐部活动", refused.getMessage());
        verify(mapper, never()).selectActiveWaitlist(anyLong(), anyLong(), anyLong());
    }
}
