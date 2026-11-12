package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.GiftPlayFact;
import com.chengyinhub.business.domain.RegistrationSeatInvitation;
import com.chengyinhub.business.domain.RegistrationSeatInvitationRequestRecord;
import com.chengyinhub.business.domain.dto.SeatInvitationActionRequest;
import com.chengyinhub.business.domain.dto.SeatInvitationClaimRequest;
import com.chengyinhub.business.domain.dto.SeatInvitationCreateRequest;
import com.chengyinhub.business.domain.vo.SeatInvitationOwnerViewVO;
import com.chengyinhub.business.domain.vo.SeatInvitationVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.RegistrationSeatInvitationMapper;
import com.chengyinhub.business.mapper.RegistrationSeatInvitationRequestMapper;
import com.chengyinhub.business.service.impl.RegistrationSeatInvitationServiceImpl;
import com.chengyinhub.business.service.support.SeatInvitationTokenCodec;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import com.chengyinhub.business.mapper.RegistrationSeatMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 内含席位邀请生命周期：归属、容量守恒、幂等、令牌失效、领取后释放闸。
 *
 * <p>纯 mock 验证调用方决策与状态迁移，不冒充 MySQL 唯一键/行锁的真实并发证明
 * （那部分在 {@code RegistrationSeatInvitationMySqlIT}）。
 */
class RegistrationSeatInvitationServiceTest {

    private RegistrationSeatInvitationServiceImpl service;
    private CmsRegistrationMapper registrations;
    private CmsActivityMapper activities;
    private CmsTopicMapper topics;
    private RegistrationSeatMapper seatFacts;
    private RegistrationSeatInvitationMapper invitations;
    private RegistrationSeatInvitationRequestMapper requestRecords;
    private IMarketingGiftPlayEligibility gift;

    private final Map<Long, RegistrationSeatInvitation> rows = new LinkedHashMap<>();
    private final Map<String, RegistrationSeatInvitationRequestRecord> byRequest = new LinkedHashMap<>();
    private final Set<Long> used = new LinkedHashSet<>();
    private final AtomicLong ids = new AtomicLong(500);
    private CmsRegistration order;
    private CmsActivity activity;

    @BeforeEach
    void setup() {
        topics = mock(CmsTopicMapper.class);
        seatFacts = mock(RegistrationSeatMapper.class);
        registrations = mock(CmsRegistrationMapper.class);
        activities = mock(CmsActivityMapper.class);
        topics = mock(CmsTopicMapper.class);
        invitations = mock(RegistrationSeatInvitationMapper.class);
        requestRecords = mock(RegistrationSeatInvitationRequestMapper.class);
        gift = mock(IMarketingGiftPlayEligibility.class);

        order = new CmsRegistration();
        order.setId(31L); order.setOwnerType(2L); order.setOwnerId(61L); order.setMemberId(51L);
        order.setOrderNum(3L); order.setRegistrationStatus(2); order.setPaymentStatus(2);
        order.setPurchaseKind(1);
        activity = new CmsActivity();
        activity.setId(61L); activity.setTopicId(41L); activity.setDelFlag(0);
        activity.setEndDate(new Date(System.currentTimeMillis() + 86_400_000L));

        when(registrations.selectCmsRegistrationById(31L)).thenReturn(order);
        when(registrations.selectCmsRegistrationByIdForUpdate(31L)).thenReturn(order);
        when(registrations.hasActiveManualRefundCaseForUpdate(31L)).thenReturn(false);
        when(activities.selectCmsActivityByIdForUpdate(61L)).thenReturn(activity);
        when(activities.selectCmsActivityById(61L)).thenReturn(activity);
        when(gift.playFacts(anyLong(), anyLong(), anyLong())).thenReturn(List.of());
        when(invitations.hasOriginalUseFacts(anyLong(), anyLong(), anyLong()))
                .thenAnswer(call -> used.contains(call.getArgument(0, Long.class)));

        when(requestRecords.selectByRequestId(anyLong(), anyString()))
                .thenAnswer(call -> byRequest.get(ledgerKey(
                        call.getArgument(0, Long.class), call.getArgument(1, String.class))));
        when(requestRecords.selectByRequestIdForUpdate(anyLong(), anyString()))
                .thenAnswer(call -> byRequest.get(ledgerKey(
                        call.getArgument(0, Long.class), call.getArgument(1, String.class))));
        doAnswer(call -> {
            RegistrationSeatInvitationRequestRecord record = call.getArgument(0);
            byRequest.put(ledgerKey(record.getMemberId(), record.getRequestId()), record);
            return 1;
        }).when(requestRecords).insert(any());
        when(invitations.countClaimedByRecipient(anyLong(), anyLong())).thenAnswer(call -> {
            long registrationId = call.getArgument(0, Long.class);
            long memberId = call.getArgument(1, Long.class);
            int count = 0;
            for (RegistrationSeatInvitation row : rows.values()) {
                if (row.getRegistrationId() != null && row.getRegistrationId() == registrationId
                        && Integer.valueOf(RegistrationSeatInvitation.STATUS_CLAIMED).equals(row.getStatus())
                        && row.getInviteeMemberId() != null && row.getInviteeMemberId() == memberId) {
                    count++;
                }
            }
            return count;
        });

        when(invitations.insert(any())).thenAnswer(call -> {
            RegistrationSeatInvitation row = call.getArgument(0);
            row.setId(ids.incrementAndGet());
            rows.put(row.getId(), row);
            return 1;
        });
        when(invitations.selectById(anyLong()))
                .thenAnswer(call -> rows.get(call.getArgument(0, Long.class)));
        when(invitations.selectByIdForUpdate(anyLong()))
                .thenAnswer(call -> rows.get(call.getArgument(0, Long.class)));
        when(invitations.selectByTokenHash(anyString())).thenAnswer(call -> {
            String hash = call.getArgument(0, String.class);
            for (RegistrationSeatInvitation row : rows.values()) {
                if (hash.equals(row.getTokenHash())) return row;
            }
            return null;
        });
        when(invitations.selectByRegistration(anyLong()))
                .thenAnswer(call -> new ArrayList<>(rows.values()));
        when(invitations.selectActiveByRegistrationForUpdate(anyLong())).thenAnswer(call -> {
            List<RegistrationSeatInvitation> active = new ArrayList<>();
            for (RegistrationSeatInvitation row : rows.values()) if (row.active()) active.add(row);
            return active;
        });
        when(invitations.maxGeneration(anyLong(), anyInt())).thenAnswer(call -> {
            long registrationId = call.getArgument(0, Long.class);
            int seatNo = call.getArgument(1, Integer.class);
            int max = 0;
            for (RegistrationSeatInvitation row : rows.values()) {
                if (row.getRegistrationId() != null && row.getRegistrationId() == registrationId
                        && row.getSeatNo() != null && row.getSeatNo() == seatNo) {
                    max = Math.max(max, row.getGeneration() == null ? 0 : row.getGeneration());
                }
            }
            return max;
        });
        when(invitations.claim(anyLong(), anyLong(), any())).thenAnswer(call -> {
            RegistrationSeatInvitation row = rows.get(call.getArgument(0, Long.class));
            Date now = call.getArgument(2, Date.class);
            if (row == null || !row.pending() || row.getExpiresAt() == null || !row.getExpiresAt().after(now)) return 0;
            row.setStatus(RegistrationSeatInvitation.STATUS_CLAIMED);
            row.setInviteeMemberId(call.getArgument(1, Long.class));
            row.setClaimedAt(now);
            return 1;
        });
        when(invitations.transition(anyLong(), anyInt(), anyInt(), any())).thenAnswer(call -> {
            RegistrationSeatInvitation row = rows.get(call.getArgument(0, Long.class));
            int from = call.getArgument(1, Integer.class);
            if (row == null || row.getStatus() == null || row.getStatus() != from) return 0;
            row.setStatus(call.getArgument(2, Integer.class));
            row.setUpdateTime(call.getArgument(3, Date.class));
            return 1;
        });
        when(invitations.expirePending(anyLong(), any())).thenAnswer(call -> {
            Date now = call.getArgument(1, Date.class);
            int changed = 0;
            for (RegistrationSeatInvitation row : rows.values()) {
                if (row.pending() && row.getExpiresAt() != null && !row.getExpiresAt().after(now)) {
                    row.setStatus(RegistrationSeatInvitation.STATUS_EXPIRED);
                    changed++;
                }
            }
            return changed;
        });
        service = new RegistrationSeatInvitationServiceImpl(registrations, activities, topics,
                seatFacts, invitations, requestRecords, gift, "test-secret", 24L);
    }

    @Test
    void buyerInvitesIncludedSeatAndOutsiderIsDenied() {
        SeatInvitationVO issued = service.invite(51L, create(null, "r-1"));
        assertEquals(Integer.valueOf(2), issued.getSeatNo());
        assertEquals("PENDING", issued.getStatusLabel());
        assertNotNull(issued.getToken());
        assertTrue(issued.getToken().startsWith("st1."));
        assertEquals(1, rows.size());
        RegistrationSeatInvitation stored = rows.values().iterator().next();
        assertEquals(SeatInvitationTokenCodec.sha256Hex(issued.getToken()), stored.getTokenHash());
        assertNotEquals(issued.getToken(), stored.getTokenHash());

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.invite(99L, create(null, "r-outsider")));
        assertEquals(Integer.valueOf(403), denied.getCode());
        assertEquals(1, rows.size());
        assertTrue(byRequest.containsKey("51|r-1"));
        assertFalse(byRequest.containsKey("99|r-outsider"));
    }

    @Test
    void sameRequestReplaysSameInvitationAndDifferentPayloadConflicts() {
        SeatInvitationVO first = service.invite(51L, create(null, "r-replay"));
        SeatInvitationVO replay = service.invite(51L, create(null, "r-replay"));
        assertEquals(first.getInvitationId(), replay.getInvitationId());
        assertEquals(first.getToken(), replay.getToken());
        assertTrue(replay.getReplayed());
        assertEquals(1, rows.size());
        assertEquals(1, byRequest.size());

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.invite(51L, create(3, "r-replay")));
        assertEquals(Integer.valueOf(409), conflict.getCode());
        assertEquals(1, rows.size());
    }

    @Test
    void lastIncludedSeatIsReservedAndCancellationReopensItWithNewGeneration() {
        SeatInvitationVO seat2 = service.invite(51L, create(null, "r-2"));
        SeatInvitationVO seat3 = service.invite(51L, create(null, "r-3"));
        assertEquals(Integer.valueOf(3), seat3.getSeatNo());

        SeatInvitationOwnerViewVO full = service.ownerView(51L, 31L);
        assertEquals(Integer.valueOf(3), full.getSeatCount());
        assertEquals(Integer.valueOf(1), full.getBuyerSeatNo());
        assertEquals(Integer.valueOf(2), full.getInvitableSeats());
        assertEquals(Integer.valueOf(2), full.getPendingSeats());
        assertEquals(Integer.valueOf(0), full.getFreeSeats());

        ServiceException capacity = assertThrows(ServiceException.class,
                () -> service.invite(51L, create(null, "r-4")));
        assertEquals(Integer.valueOf(409), capacity.getCode());

        service.cancel(51L, action(seat2.getInvitationId(), "c-2"));
        SeatInvitationOwnerViewVO reopened = service.ownerView(51L, 31L);
        assertEquals(Integer.valueOf(1), reopened.getPendingSeats());
        assertEquals(Integer.valueOf(1), reopened.getFreeSeats());

        SeatInvitationVO again = service.invite(51L, create(null, "r-5"));
        assertEquals(Integer.valueOf(2), again.getSeatNo());
        assertEquals(Integer.valueOf(2), again.getGeneration());
        assertNotEquals(seat2.getToken(), again.getToken());

        ServiceException former = assertThrows(ServiceException.class,
                () -> service.claim(99L, claim(seat2.getToken(), "old-token")));
        assertEquals(Integer.valueOf(409), former.getCode());
    }

    @Test
    void forgedExpiredAndCancelledTokensFail() {
        SeatInvitationVO issued = service.invite(51L, create(null, "r-f"));
        String token = issued.getToken();
        String forged = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.claim(99L, claim(forged, "c-forged"))).getCode());

        rows.get(issued.getInvitationId()).setExpiresAt(new Date(System.currentTimeMillis() - 1000L));
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.claim(99L, claim(token, "c-expired"))).getCode());
        assertEquals("EXPIRED", service.status(51L, issued.getInvitationId()).getStatusLabel());
        assertEquals(Integer.valueOf(RegistrationSeatInvitation.STATUS_PENDING),
                rows.get(issued.getInvitationId()).getStatus(), "GET/status 只投影过期，不写库");
    }

    @Test
    void claimBindsRecipientThenRunningReleaseIsDenied() {
        SeatInvitationVO issued = service.invite(51L, create(2, "r-claim"));
        SeatInvitationVO claimed = service.claim(99L, claim(issued.getToken(), "c-1"));
        assertEquals("CLAIMED", claimed.getStatusLabel());
        assertEquals(Long.valueOf(99L), claimed.getInviteeMemberId());

        SeatInvitationVO replay = service.claim(99L, claim(issued.getToken(), "c-1"));
        assertTrue(replay.getReplayed());
        assertEquals(claimed.getInvitationId(), replay.getInvitationId());

        assertEquals(51L, order.getMemberId());
        assertEquals(Integer.valueOf(2), order.getPaymentStatus());
        assertEquals(Long.valueOf(3L), order.getOrderNum());

        used.add(99L);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.release(51L, action(issued.getInvitationId(), "rel-1"))).getCode());
        assertEquals(Integer.valueOf(RegistrationSeatInvitation.STATUS_CLAIMED),
                rows.get(issued.getInvitationId()).getStatus());

        // 运行状态结束也不能清掉原始使用事实。
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.release(51L, action(issued.getInvitationId(), "rel-after-completed"))).getCode());
        verify(invitations, never()).transition(eq(issued.getInvitationId()), eq(1), eq(4), any());
    }

    @Test
    void unusedClaimedSeatCanBeReleasedAfterOriginalRegistrationLock() {
        SeatInvitationVO issued = service.invite(51L, create(2, "unused-invite"));
        service.claim(99L, claim(issued.getToken(), "unused-claim"));
        clearInvocations(activities, registrations, invitations);
        SeatInvitationVO released = service.release(51L, action(issued.getInvitationId(), "unused-release"));
        assertEquals("RELEASED", released.getStatusLabel());
        org.mockito.InOrder locks = inOrder(activities, registrations, invitations);
        locks.verify(activities).selectCmsActivityByIdForUpdate(61L);
        locks.verify(registrations).selectCmsRegistrationByIdForUpdate(31L);
        locks.verify(invitations).selectByIdForUpdate(issued.getInvitationId());
        locks.verify(invitations).hasOriginalUseFacts(99L, 41L, 61L);
        locks.verify(invitations).transition(eq(issued.getInvitationId()), eq(1), eq(4), any());
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.claim(99L, claim(issued.getToken(), "c-after-release"))).getCode());
        assertEquals(2, service.ownerView(51L, 31L).getFreeSeats());
        assertEquals(51L, order.getMemberId());
        assertEquals(3L, order.getOrderNum());
    }

    @Test
    void unknownOrUnavailableUseFactsNeverReleaseTheSeat() {
        SeatInvitationVO issued = service.invite(51L, create(2, "unknown-invite"));
        service.claim(99L, claim(issued.getToken(), "unknown-claim"));
        when(invitations.hasOriginalUseFacts(99L, 41L, 61L)).thenReturn(null);
        assertThrows(ServiceException.class,
                () -> service.release(51L, action(issued.getInvitationId(), "unknown-release")));
        when(invitations.hasOriginalUseFacts(99L, 41L, 61L)).thenThrow(
                new IllegalStateException("authoritative use table unavailable"));
        assertThrows(IllegalStateException.class,
                () -> service.release(51L, action(issued.getInvitationId(), "failed-release")));
        when(seatFacts.selectByRegistration(31L)).thenReturn(null);
        assertThrows(ServiceException.class,
                () -> service.release(51L, action(issued.getInvitationId(), "unknown-verification")));
        assertEquals("CLAIMED", service.status(51L, issued.getInvitationId()).getStatusLabel());
        verify(invitations, never()).transition(eq(issued.getInvitationId()), eq(1), eq(4), any());
    }

    @Test
    void differentActorsCanUseTheSameRequestIdWithoutReplayingEachOther() {
        SeatInvitationVO issued = service.invite(51L, create(2, "same-key"));
        SeatInvitationVO claimed = service.claim(99L, claim(issued.getToken(), "same-key"));
        assertEquals("CLAIMED", claimed.getStatusLabel());
        assertFalse(Boolean.TRUE.equals(claimed.getReplayed()));
        assertNotNull(byRequest.get(ledgerKey(51L, "same-key")));
        assertNotNull(byRequest.get(ledgerKey(99L, "same-key")));
    }

    @Test
    void unrelatedLegacyMarketingSt1TokenCannotClaimASeat() {
        service.invite(51L, create(2, "seat-isolation"));
        String legacy = com.chengyinhub.business.service.support.MarketingClaimTokenCodec.issue(
                "st1", "legacymarketing01", System.currentTimeMillis() / 1000L + 3600L, "test-secret");
        assertTrue(SeatInvitationTokenCodec.verify(legacy, System.currentTimeMillis() / 1000L, "test-secret").valid);
        assertThrows(ServiceException.class, () -> service.claim(99L, claim(legacy, "legacy-claim")));
        verify(invitations, never()).claim(anyLong(), anyLong(), any());
    }

    @Test
    void persistedNonceExpiryAndBuyerScopeMustMatchTheSignedSeatToken() {
        SeatInvitationVO issued = service.invite(51L, create(2, "scope-invite"));
        RegistrationSeatInvitation row = rows.get(issued.getInvitationId());
        String nonce = row.getTokenNonce();
        row.setTokenNonce("0000000000000000");
        assertThrows(ServiceException.class, () -> service.claim(99L, claim(issued.getToken(), "bad-nonce")));
        row.setTokenNonce(nonce);
        Date expiresAt = row.getExpiresAt();
        row.setExpiresAt(new Date(expiresAt.getTime() + 1000L));
        assertThrows(ServiceException.class, () -> service.claim(99L, claim(issued.getToken(), "bad-expiry")));
        row.setExpiresAt(expiresAt);
        row.setOwnerMemberId(52L);
        assertThrows(ServiceException.class, () -> service.claim(99L, claim(issued.getToken(), "bad-buyer")));
        verify(invitations, never()).claim(anyLong(), anyLong(), any());
    }

    @Test
    void expiredPendingReadsProjectStateWithoutWritingOrTakingRegistrationLocks() {
        SeatInvitationVO issued = service.invite(51L, create(2, "read-expiry"));
        RegistrationSeatInvitation row = rows.get(issued.getInvitationId());
        row.setExpiresAt(new Date(System.currentTimeMillis() - 1000L));
        clearInvocations(invitations, registrations, activities, topics);
        assertEquals("EXPIRED", service.status(51L, row.getId()).getStatusLabel());
        assertEquals(2, service.ownerView(51L, 31L).getFreeSeats());
        assertTrue(row.pending(), "GET must not persist projected expiry");
        verify(invitations, never()).expirePending(anyLong(), any());
        verify(invitations, never()).transition(anyLong(), anyInt(), anyInt(), any());
        verify(registrations, never()).selectCmsRegistrationByIdForUpdate(anyLong());
        verify(activities, never()).selectCmsActivityByIdForUpdate(anyLong());
        verify(topics, never()).selectCmsTopicByIdForUpdate(anyLong());
    }

    @Test
    void buyerCannotClaimOwnInviteAndPendingInviteAloneGrantsNothing() {
        SeatInvitationVO issued = service.invite(51L, create(2, "r-self"));
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.claim(51L, claim(issued.getToken(), "c-self"))).getCode());
        assertEquals(Integer.valueOf(RegistrationSeatInvitation.STATUS_PENDING),
                rows.get(issued.getInvitationId()).getStatus());
    }

    @Test
    void refundedOriginalCannotBeInvitedOrClaimed() {
        SeatInvitationVO issued = service.invite(51L, create(2, "r-paid"));
        order.setPaymentStatus(4);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.claim(99L, claim(issued.getToken(), "c-refunded"))).getCode());
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.invite(51L, create(null, "r-refunded"))).getCode());
    }


    @Test
    void sameRecipientCannotClaimTwoSeatsOnOneOrder() {
        SeatInvitationVO first = service.invite(51L, create(2, "r-dup-1"));
        service.claim(99L, claim(first.getToken(), "c-dup-1"));
        SeatInvitationVO second = service.invite(51L, create(3, "r-dup-2"));
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.claim(99L, claim(second.getToken(), "c-dup-2"))).getCode());
    }

    private String ledgerKey(Long memberId, String requestId) {
        return memberId + "|" + requestId;
    }

    @Test
    void verifiedMerchantSeatCannotBeReleased() {
        SeatInvitationVO issued = service.invite(51L, create(2, "r-verified"));
        service.claim(99L, claim(issued.getToken(), "c-verified"));
        com.chengyinhub.business.domain.RegistrationSeat seat =
                new com.chengyinhub.business.domain.RegistrationSeat();
        seat.setSeatNo(2);
        seat.setStatus(1);
        when(seatFacts.selectByRegistration(31L)).thenReturn(List.of(seat));
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.release(51L, action(issued.getInvitationId(), "rel-verified"))).getCode());
        assertEquals(Integer.valueOf(RegistrationSeatInvitation.STATUS_CLAIMED),
                rows.get(issued.getInvitationId()).getStatus());
    }
    @Test
    void giftControlledRowCannotBeSharedAsIncludedSeat() {
        GiftPlayFact fact = new GiftPlayFact();
        fact.setRegistrationId(31L);
        fact.setStatus(GiftPlayFact.STATUS_PENDING);
        when(gift.playFacts(eq(51L), anyLong(), anyLong())).thenReturn(List.of(fact));
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.invite(51L, create(null, "r-gift"))).getCode());
        assertTrue(rows.isEmpty());
    }

    @Test
    void invitationForAnotherTopicCannotBeClaimedInThisScope() {
        SeatInvitationVO issued = service.invite(51L, create(2, "r-topic"));
        rows.get(issued.getInvitationId()).setTopicId(42L);
        ServiceException mismatch = assertThrows(ServiceException.class,
                () -> service.claim(99L, claim(issued.getToken(), "c-topic")));
        assertEquals(Integer.valueOf(409), mismatch.getCode());
        assertTrue(mismatch.getMessage().contains("主题"));
    }

    @Test
    void pendingExpiryReleasesOnlyUnclaimedReservation() {
        SeatInvitationVO pending = service.invite(51L, create(2, "r-exp-pending"));
        SeatInvitationVO claimed = service.invite(51L, create(3, "r-exp-claimed"));
        service.claim(99L, claim(claimed.getToken(), "c-exp-claimed"));
        rows.get(pending.getInvitationId()).setExpiresAt(new Date(System.currentTimeMillis() - 1000L));

        SeatInvitationOwnerViewVO view = service.ownerView(51L, 31L);
        assertEquals(Integer.valueOf(0), view.getPendingSeats());
        assertEquals(Integer.valueOf(1), view.getClaimedSeats());
        assertEquals(Integer.valueOf(1), view.getExpiredSeats());
        assertEquals(Integer.valueOf(1), view.getFreeSeats());
        assertEquals(Integer.valueOf(RegistrationSeatInvitation.STATUS_CLAIMED),
                rows.get(claimed.getInvitationId()).getStatus());
    }

    private SeatInvitationCreateRequest create(Integer seatNo, String requestId) {
        SeatInvitationCreateRequest request = new SeatInvitationCreateRequest();
        request.setRegistrationId(31L);
        request.setSeatNo(seatNo);
        request.setRequestId(requestId);
        return request;
    }

    private SeatInvitationClaimRequest claim(String token, String requestId) {
        SeatInvitationClaimRequest request = new SeatInvitationClaimRequest();
        request.setToken(token);
        request.setRequestId(requestId);
        return request;
    }

    private SeatInvitationActionRequest action(Long invitationId, String requestId) {
        SeatInvitationActionRequest request = new SeatInvitationActionRequest();
        request.setInvitationId(invitationId);
        request.setRequestId(requestId);
        return request;
    }
}
