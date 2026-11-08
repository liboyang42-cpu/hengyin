package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RegistrationSeat;
import com.chengyinhub.business.domain.vo.PlayerJourneySeatsVO;
import com.chengyinhub.business.domain.vo.SeatInvitationOwnerViewVO;
import com.chengyinhub.business.domain.vo.SeatInvitationVO;
import com.chengyinhub.business.mapper.RegistrationSeatMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRegistrationSeatInvitationService;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P5 席位摘要：购票人专属、冻结席位数、已核销/未核销/过期状态、受让人隐私与只读幂等。
 */
class PlayerJourneySeatsApplicationTest {

    private static final Long BUYER = 11L;
    private static final Long OTHER = 22L;
    private static final Long REGISTRATION = 500L;
    private static final Long ACTIVITY = 900L;
    private static final Long TOPIC = 7L;

    private PlayerJourneySeatsApplication application;
    private ICmsRegistrationService registrationService;
    private ICmsActivityService activityService;
    private RegistrationSeatMapper seatMapper;
    private IRegistrationSeatInvitationService seatInvitations;

    @BeforeEach
    void setUp() {
        registrationService = mock(ICmsRegistrationService.class);
        activityService = mock(ICmsActivityService.class);
        seatMapper = mock(RegistrationSeatMapper.class);
        seatInvitations = mock(IRegistrationSeatInvitationService.class);
        application = new PlayerJourneySeatsApplication();
        ReflectionTestUtils.setField(application, "cmsRegistrationService", registrationService);
        ReflectionTestUtils.setField(application, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(application, "registrationSeatMapper", seatMapper);
        ReflectionTestUtils.setField(application, "seatInvitations", seatInvitations);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(seatInvitations.ownerView(BUYER, REGISTRATION)).thenReturn(inviteView(2, 0, 0, List.of()));
    }

    @Test
    void buyerSeesFrozenSeatCountAndSeatStates() {
        when(registrationService.selectCmsRegistrationById(REGISTRATION)).thenReturn(registration(BUYER, 3L, null, 2));
        RegistrationSeat verified = seat(1, 1, new Date(1_700_000_000_000L));
        RegistrationSeat reserved = seat(2, 0, null);
        when(seatMapper.selectByRegistration(REGISTRATION)).thenReturn(List.of(verified, reserved));

        PlayerJourneySeatsVO vo = (PlayerJourneySeatsVO) application.summary(BUYER, REGISTRATION)
                .get(AjaxResult.DATA_TAG);

        assertEquals(3, vo.getSeatCount().intValue(), "order_num 是购票时固化的席位数");
        assertEquals(1, vo.getVerifiedSeats().intValue());
        assertEquals(1, vo.getReservedSeats().intValue());
        assertEquals(1, vo.getAvailableSeats().intValue());
        assertEquals(0, vo.getExpiredSeats().intValue());
        assertEquals(3, vo.getSeats().size());
        assertEquals(PlayerJourneySeatsVO.SEAT_VERIFIED, vo.getSeats().get(0).getState());
        assertEquals(PlayerJourneySeatsVO.SEAT_RESERVED, vo.getSeats().get(1).getState());
        assertEquals(PlayerJourneySeatsVO.SEAT_AVAILABLE, vo.getSeats().get(2).getState());
        assertEquals("BUYER", vo.getFinancialOwner(), "财务归属仍是购票人");
        assertTrue(vo.getBuyerIsCaller());
        assertFalse(vo.getRecipientIdentityVisible());
        assertEquals(TOPIC, vo.getTopicId());
        assertEquals(2, vo.getFreeSeats().intValue(), "可邀请席位 = N-1 - pending - claimed");
        assertEquals(0, vo.getInvitedSeats().intValue());
        assertTrue(vo.getUnavailableFacts().isEmpty());
        assertFalse(vo.getReadOnly());
        assertTrue(vo.getSeatCancelSupported());
        assertTrue(vo.getSeatReleaseSupported());
    }

    @Test
    void nonBuyerDeniedBeforeAnySeatRead() {
        when(registrationService.selectCmsRegistrationById(REGISTRATION)).thenReturn(registration(OTHER, 3L, null, 2));

        assertFalse(application.summary(BUYER, REGISTRATION).isSuccess());
        verify(seatMapper, never()).selectByRegistration(anyLong());
    }

    @Test
    void expiredOrderLeavesUnverifiedSeatsExpiredButKeepsVerifiedFacts() {
        when(registrationService.selectCmsRegistrationById(REGISTRATION))
                .thenReturn(registration(BUYER, 2L, new Date(1_600_000_000_000L), 2));
        when(seatMapper.selectByRegistration(REGISTRATION))
                .thenReturn(List.of(seat(1, 1, new Date(1_650_000_000_000L))));

        PlayerJourneySeatsVO vo = (PlayerJourneySeatsVO) application.summary(BUYER, REGISTRATION)
                .get(AjaxResult.DATA_TAG);

        assertEquals(1, vo.getVerifiedSeats().intValue());
        assertEquals(0, vo.getAvailableSeats().intValue());
        assertEquals(1, vo.getExpiredSeats().intValue());
        assertEquals(PlayerJourneySeatsVO.SEAT_EXPIRED, vo.getSeats().get(1).getState());
        assertEquals(new Date(1_600_000_000_000L), vo.getExpiresAt(), "服务端 expiresAt 原样下发");
    }

    @Test
    void legacyNullOrderNumIsSingleSeatAndExplorePassNeverExpands() {
        when(registrationService.selectCmsRegistrationById(REGISTRATION)).thenReturn(registration(BUYER, null, null, 2));
        when(seatMapper.selectByRegistration(REGISTRATION)).thenReturn(List.of());
        PlayerJourneySeatsVO legacy = (PlayerJourneySeatsVO) application.summary(BUYER, REGISTRATION)
                .get(AjaxResult.DATA_TAG);
        assertEquals(1, legacy.getSeatCount().intValue());

        when(registrationService.selectCmsRegistrationById(REGISTRATION))
                .thenReturn(registration(BUYER, 5L, null, PurchaseKind.EXPLORE_PASS));
        PlayerJourneySeatsVO explore = (PlayerJourneySeatsVO) application.summary(BUYER, REGISTRATION)
                .get(AjaxResult.DATA_TAG);
        assertEquals(1, explore.getSeatCount().intValue(), "自由探索票不按 order_num 扩席位");
    }

    @Test
    void repeatedReadIsStableAndWritesNothing() {
        when(registrationService.selectCmsRegistrationById(REGISTRATION)).thenReturn(registration(BUYER, 2L, null, 2));
        when(seatMapper.selectByRegistration(REGISTRATION)).thenReturn(List.of());

        PlayerJourneySeatsVO first = (PlayerJourneySeatsVO) application.summary(BUYER, REGISTRATION)
                .get(AjaxResult.DATA_TAG);
        PlayerJourneySeatsVO second = (PlayerJourneySeatsVO) application.summary(BUYER, REGISTRATION)
                .get(AjaxResult.DATA_TAG);

        assertEquals(first.getSeatCount(), second.getSeatCount());
        assertEquals(first.getAvailableSeats(), second.getAvailableSeats());
        verify(seatMapper, times(2)).selectByRegistration(REGISTRATION);
        verify(seatMapper, never()).insertSeat(any());
        verify(seatMapper, never()).markVerified(any());
        verify(seatMapper, never()).selectByRegistrationForUpdate(anyLong());
    }

    @Test
    void nonSeatOwnerTypeDenied() {
        CmsRegistration row = registration(BUYER, 2L, null, 2);
        row.setOwnerType(3L);
        when(registrationService.selectCmsRegistrationById(REGISTRATION)).thenReturn(row);
        assertFalse(application.summary(BUYER, REGISTRATION).isSuccess());
    }

    @Test
    void pendingAndClaimedIncludedSeatsAreProjectedWithoutBuyerMoneyChange() {
        when(registrationService.selectCmsRegistrationById(REGISTRATION))
                .thenReturn(registration(BUYER, 3L, null, 2));
        when(seatMapper.selectByRegistration(REGISTRATION)).thenReturn(List.of());
        SeatInvitationVO pending = invitation(101L, 2, "PENDING", null);
        SeatInvitationVO claimed = invitation(102L, 3, "CLAIMED", 99L);
        when(seatInvitations.ownerView(BUYER, REGISTRATION))
                .thenReturn(inviteView(0, 1, 1, List.of(pending, claimed)));

        PlayerJourneySeatsVO vo = (PlayerJourneySeatsVO) application.summary(BUYER, REGISTRATION)
                .get(AjaxResult.DATA_TAG);

        assertEquals(PlayerJourneySeatsVO.SEAT_BUYER, vo.getSeats().get(0).getState());
        assertEquals(PlayerJourneySeatsVO.SEAT_INVITED, vo.getSeats().get(1).getState());
        assertEquals(PlayerJourneySeatsVO.SEAT_CLAIMED, vo.getSeats().get(2).getState());
        assertEquals(Long.valueOf(99L), vo.getSeats().get(2).getInviteeMemberId());
        assertEquals(0, vo.getFreeSeats().intValue());
        assertEquals(2, vo.getInvitedSeats().intValue());
        assertTrue(vo.getSeatReleaseSupported());
        assertEquals("BUYER", vo.getFinancialOwner());
    }

    private static SeatInvitationOwnerViewVO inviteView(int free, int pending, int claimed,
                                                         List<SeatInvitationVO> invitations) {
        SeatInvitationOwnerViewVO view = new SeatInvitationOwnerViewVO();
        view.setSeatCount(3);
        view.setFreeSeats(free);
        view.setPendingSeats(pending);
        view.setClaimedSeats(claimed);
        view.setInvitations(invitations);
        return view;
    }

    private static SeatInvitationVO invitation(Long id, Integer seatNo, String status, Long invitee) {
        SeatInvitationVO vo = new SeatInvitationVO();
        vo.setInvitationId(id);
        vo.setSeatNo(seatNo);
        vo.setStatusLabel(status);
        vo.setInviteeMemberId(invitee);
        return vo;
    }

    private static CmsRegistration registration(Long buyer, Long orderNum, Date expiresAt, Integer purchaseKind) {
        CmsRegistration row = new CmsRegistration();
        row.setId(REGISTRATION);
        row.setMemberId(buyer);
        row.setOwnerType(2L);
        row.setOwnerId(ACTIVITY);
        row.setOrderNum(orderNum);
        row.setExpiresAt(expiresAt);
        row.setPurchaseKind(purchaseKind);
        row.setPaymentStatus(2);
        row.setRegistrationStatus(2);
        return row;
    }

    private static RegistrationSeat seat(int seatNo, int status, Date verifiedAt) {
        RegistrationSeat row = new RegistrationSeat();
        row.setRegistrationId(REGISTRATION);
        row.setSeatNo(seatNo);
        row.setStatus(status);
        row.setVerifiedAt(verifiedAt);
        return row;
    }
}
