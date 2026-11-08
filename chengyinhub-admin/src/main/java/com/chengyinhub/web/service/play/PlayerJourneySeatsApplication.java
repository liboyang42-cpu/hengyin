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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.chengyinhub.common.core.domain.AjaxResult.error;

/**
 * 已付多人票的席位摘要（P5）：只读投影 + 真实内含席位邀请状态。
 *
 * <p>第 1 席是购票人自用；第 2..N 席可邀请（最多 N-1）。邀请/领取/撤销/释放的写口在
 * {@code /api/play/seat-invitations}；本类只把同一事实投影给玩家页，不建席位、不初始化身份、
 * 不改核销状态。购票人财务归属仍是 {@code cms_registration.member_id}。
 */
@Service
public class PlayerJourneySeatsApplication {

    static final String FINANCIAL_OWNER_BUYER = "BUYER";
    static final String SUPPORT_NOTE = "SEAT_CANCEL_OR_RELEASE_SUPPORTED;RELEASE_REQUIRES_UNUSED_AND_UNVERIFIED_FACTS";

    @Autowired
    private ICmsRegistrationService cmsRegistrationService;
    @Autowired
    private ICmsActivityService cmsActivityService;
    @Autowired
    private RegistrationSeatMapper registrationSeatMapper;
    @Autowired
    private IRegistrationSeatInvitationService seatInvitations;

    public AjaxResult summary(Long actorMemberId, Long registrationId) {
        Long memberId = actorMemberId;
        if (memberId == null || memberId <= 0L) return error(403, "请先登录");
        if (registrationId == null || registrationId <= 0L) return error("参数有误");
        CmsRegistration registration = cmsRegistrationService.selectCmsRegistrationById(registrationId);
        if (registration == null) return error(403, "报名单不存在或无权读取");
        // 财务归属只在购票人身上；受让人/其他成员读不到这张单的席位与订单事实。
        if (!memberId.equals(registration.getMemberId())) return error(403, "席位管理仅购票人可读");
        Long ownerType = registration.getOwnerType();
        if (!Long.valueOf(1L).equals(ownerType) && !Long.valueOf(2L).equals(ownerType)) {
            return error("该订单不含主题席位");
        }

        Long topicId = topicOf(registration);
        int seatCount = seatCountOf(registration);
        SeatInvitationOwnerViewVO inviteView = seatInvitations == null ? null
                : seatInvitations.ownerView(memberId, registrationId);
        Map<Integer, SeatInvitationVO> invitationBySeat = invitationBySeat(inviteView);

        List<RegistrationSeat> rows = registrationSeatMapper.selectByRegistration(registrationId);
        Map<Integer, RegistrationSeat> byNo = new HashMap<>();
        for (RegistrationSeat row : rows == null ? List.<RegistrationSeat>of() : rows) {
            if (row != null && row.getSeatNo() != null) byNo.put(row.getSeatNo(), row);
        }
        boolean expired = registration.getExpiresAt() != null
                && registration.getExpiresAt().before(new Date());

        PlayerJourneySeatsVO vo = new PlayerJourneySeatsVO();
        vo.setRegistrationId(registrationId);
        vo.setOwnerType(ownerType);
        vo.setOwnerId(registration.getOwnerId());
        vo.setTopicId(topicId);
        vo.setSeatCount(seatCount);
        vo.setExpiresAt(registration.getExpiresAt());
        vo.setServerNow(System.currentTimeMillis());
        vo.setFinancialOwner(FINANCIAL_OWNER_BUYER);
        vo.setBuyerIsCaller(Boolean.TRUE);
        vo.setRecipientIdentityVisible(Boolean.FALSE);
        vo.setSeatReclaimSupported(Boolean.TRUE);
        vo.setSeatReleaseSupported(Boolean.TRUE);
        vo.setSeatCancelSupported(Boolean.TRUE);
        vo.setSupportNote(SUPPORT_NOTE);
        vo.setReadOnly(Boolean.FALSE);
        vo.setFreeSeats(inviteView == null ? null : inviteView.getFreeSeats());
        vo.setInvitedSeats(inviteView == null ? null
                : Integer.valueOf(value(inviteView.getPendingSeats()) + value(inviteView.getClaimedSeats())));
        vo.setUnavailableFacts(List.of());

        List<PlayerJourneySeatsVO.Seat> seats = new ArrayList<>();
        int verified = 0;
        int reserved = 0;
        int available = 0;
        int expiredSeats = 0;
        for (int seatNo = 1; seatNo <= seatCount; seatNo++) {
            RegistrationSeat row = byNo.get(seatNo);
            SeatInvitationVO invitation = invitationBySeat.get(seatNo);
            PlayerJourneySeatsVO.Seat seat = new PlayerJourneySeatsVO.Seat();
            seat.setSeatNo(seatNo);
            if (invitation != null) {
                seat.setInvitationId(invitation.getInvitationId());
                seat.setInviteeMemberId(invitation.getInviteeMemberId());
                seat.setInvitationStatus(invitation.getStatusLabel());
                seat.setInvitationExpiresAt(invitation.getExpiresAt());
            }
            if (row != null && Integer.valueOf(1).equals(row.getStatus()) && row.getVerifiedAt() != null) {
                seat.setState(PlayerJourneySeatsVO.SEAT_VERIFIED);
                seat.setVerifiedAt(row.getVerifiedAt());
                verified++;
            } else if (seatNo == 1) {
                seat.setState(PlayerJourneySeatsVO.SEAT_BUYER);
                if (!expired) available++;
            } else if (invitation != null && "CLAIMED".equals(invitation.getStatusLabel())) {
                seat.setState(PlayerJourneySeatsVO.SEAT_CLAIMED);
            } else if (invitation != null && "PENDING".equals(invitation.getStatusLabel())) {
                seat.setState(PlayerJourneySeatsVO.SEAT_INVITED);
            } else if (row != null) {
                seat.setState(PlayerJourneySeatsVO.SEAT_RESERVED);
                reserved++;
            } else if (expired) {
                seat.setState(PlayerJourneySeatsVO.SEAT_EXPIRED);
                expiredSeats++;
            } else {
                seat.setState(PlayerJourneySeatsVO.SEAT_AVAILABLE);
                available++;
            }
            seats.add(seat);
        }
        vo.setSeats(seats);
        vo.setVerifiedSeats(verified);
        vo.setReservedSeats(reserved);
        vo.setAvailableSeats(available);
        vo.setExpiredSeats(expiredSeats);
        return AjaxResult.success(vo);
    }

    private Map<Integer, SeatInvitationVO> invitationBySeat(SeatInvitationOwnerViewVO inviteView) {
        Map<Integer, SeatInvitationVO> out = new HashMap<>();
        if (inviteView == null || inviteView.getInvitations() == null) return out;
        for (SeatInvitationVO invitation : inviteView.getInvitations()) {
            if (invitation != null && invitation.getSeatNo() != null) {
                out.put(invitation.getSeatNo(), invitation); // list is seat_no,id asc ⇒ latest wins
            }
        }
        return out;
    }

    private static int value(Integer number) { return number == null ? 0 : number; }

    /**
     * 既有席位口径：orderNum 是购票时固化的席位数；存量单席位历史订单可能为 null，
     * 按既有核销合同（order_num&gt;1 才算多人票）视为 1。
     */
    private int seatCountOf(CmsRegistration registration) {
        Long orderNum = registration.getOrderNum();
        if (orderNum == null || orderNum <= 1L) return 1;
        if (Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(registration.getPurchaseKind())) return 1;
        return (int) Math.min(Integer.MAX_VALUE, orderNum.longValue());
    }

    private Long topicOf(CmsRegistration registration) {
        if (Long.valueOf(1L).equals(registration.getOwnerType())) return registration.getOwnerId();
        CmsActivity activity = cmsActivityService.selectCmsActivityById(registration.getOwnerId());
        return activity == null ? null : activity.getTopicId();
    }
}
