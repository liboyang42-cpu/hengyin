package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/** Seat facts extend existing registration QR/group verification; no amount or settlement calculation. */
@Service
public class RegistrationSeatService {
    @Autowired private RegistrationSeatMapper seats;
    @Autowired private CmsRegistrationMapper registrations;
    @Autowired private CmsTopicMapper topics;
    @Autowired private CmsActivityMapper activities;
    @Autowired private ICmsRegistrationService registrationService;
    @Autowired private IClubMemberService clubs;
    @Autowired private BusinessAuditEventMapper audits;

    public record Receipt(Long seatId, Long registrationId, Integer seatNo, Long topicId,
                          Long activityId, Long verifierMemberId, Date verifiedAt, boolean replayed) { }

    /** Uses the existing v1 HMAC type field, not a second token format or static-code namespace. */
    public static String codeType(int seatNo) {
        if (seatNo <= 0) throw new IllegalArgumentException("席位标识无效");
        return "topic_seat_" + seatNo;
    }
    public static Integer seatNumber(String signedType) {
        if (signedType == null || !signedType.matches("topic_seat_[1-9][0-9]{0,9}")) return null;
        try { int value = Integer.parseInt(signedType.substring("topic_seat_".length())); return value > 0 ? value : null; }
        catch (NumberFormatException invalid) { return null; }
    }

    /** Lazy identity creation, not fulfillment. Never expands historical verification into seats. */
    @Transactional(rollbackFor = Exception.class)
    public RegistrationSeat prepareOwnedSeat(Long registrationId, Long actor, Integer seatNo) {
        requireActor(actor);
        CmsRegistration seed = registrationService.selectCmsRegistrationById(registrationId);
        if (seed == null || !actor.equals(seed.getMemberId())) throw denied("无权操作该报名单");
        requireTopicTicket(seed, seatNo);
        if (topics.selectCmsTopicByIdForUpdate(seed.getOwnerId()) == null) throw denied("主题不存在");
        CmsRegistration current = lockCurrent(seed);
        if (!actor.equals(current.getMemberId())) throw denied("无权操作该报名单");
        requireTopicTicket(current, seatNo);
        requirePaid(current);
        List<RegistrationSeat> rows = seats.selectByRegistrationForUpdate(registrationId);
        if (Integer.valueOf(1).equals(current.getVerificationStatus()) && rows.stream().noneMatch(this::verified)) {
            throw conflict("历史整单核销缺少逐席位证明，请联系人工核验");
        }
        RegistrationSeat selected = find(rows, seatNo);
        if (selected != null) {
            return selected; // Refreshing transport must retain the same seat, including its original receipt.
        }
        selected = new RegistrationSeat(); selected.setRegistrationId(registrationId); selected.setSeatNo(seatNo); selected.setStatus(0);
        if (seats.insertSeat(selected) != 1 || selected.getId() == null) throw new IllegalStateException("席位身份保存失败");
        return selected;
    }

    /** Original actor permission and parent locks precede any seat/aggregate mutation. */
    @Transactional(rollbackFor = Exception.class)
    public Receipt redeemGroupSeat(Long registrationId, Integer seatNo, Long activityId, Long actor) {
        requireActor(actor);
        if (registrationId == null || activityId == null || activityId <= 0) throw denied("请选择带队场次后再核销");
        CmsRegistration seed = registrationService.selectCmsRegistrationById(registrationId);
        if (seed == null) throw denied("核销码无效");
        requireTopicTicket(seed, seatNo);
        CmsTopic topic = topics.selectCmsTopicByIdForUpdate(seed.getOwnerId());
        CmsActivity activity = activities.selectCmsActivityByIdForUpdate(activityId);
        if (topic == null || activity == null || !seed.getOwnerId().equals(activity.getTopicId())
                || activity.getClubId() == null || activity.getClubId() <= 0
                || (activity.getDelFlag() != null && activity.getDelFlag() != 0) || activity.getCancelTime() != null) {
            throw denied("该带队场次不可核销");
        }
        Long clubOwner = seats.lockClubOwner(activity.getClubId());
        if (!actor.equals(clubOwner) || !clubs.isTopicOwner(topic.getId(), activity.getClubId(), actor)) {
            throw denied("仅当前承接俱乐部主理人可核销玩家票");
        }
        CmsRegistration current = lockCurrent(seed);
        requireTopicTicket(current, seatNo);
        List<RegistrationSeat> rows = seats.selectByRegistrationForUpdate(registrationId);
        RegistrationSeat seat = find(rows, seatNo);
        if (seat == null) throw conflict("席位身份不存在，请由购票人重新出示原席位码");
        CoopGroupMemberVerify binding = seats.selectGroupForUpdate(registrationId);
        if (binding != null && (!topic.getId().equals(binding.getTopicId()) || !activityId.equals(binding.getActivityId()))) {
            throw conflict("原订单已归属另一带队场次");
        }
        if (verified(seat)) {
            if (binding == null || !topic.getId().equals(seat.getTopicId()) || !activityId.equals(seat.getActivityId())
                    || seat.getVerifiedAt() == null || seat.getVerifierMemberId() == null) {
                throw conflict("原席位核销事实不完整，请联系人工核验");
            }
            return receipt(seat, true);
        }
        // Identity/read scope precedes replay; current payment/settlement gates apply only to a new seat effect.
        requirePaid(current);
        if (Integer.valueOf(1).equals(topic.getIsSettlement()) || Integer.valueOf(1).equals(activity.getIsSettlement())) {
            throw conflict("主题或场次已结算，不能新增核销事实");
        }
        if (registrations.hasActiveManualRefundCaseForUpdate(registrationId)) throw conflict("订单正在人工退款处理中，暂不可核销");
        boolean anyVerified = rows.stream().anyMatch(this::verified);
        if (binding == null) {
            if (anyVerified || Integer.valueOf(1).equals(current.getVerificationStatus())) {
                throw conflict("历史整单核销缺少逐席位证明，请联系人工核验");
            }
            // Existing REQUIRED service atomically marks aggregate used, writes original scan/group ledgers
            // and audit. For organizer scope its original payee is null; this does not calculate a seat fee.
            if (!registrationService.verifyAndSettleForGroupActivity(current, actor, activityId)) {
                throw conflict("原核销状态已变化，请联系人工核验");
            }
            binding = seats.selectGroupForUpdate(registrationId);
            if (binding == null || !topic.getId().equals(binding.getTopicId()) || !activityId.equals(binding.getActivityId())) {
                throw new IllegalStateException("原团核销归属未保存");
            }
        } else if (!anyVerified || !Integer.valueOf(1).equals(current.getVerificationStatus())) {
            throw conflict("原团核销缺少逐席位证明，请联系人工核验");
        }
        seat.setTopicId(topic.getId()); seat.setActivityId(activityId); seat.setVerifierMemberId(actor); seat.setVerifiedAt(new Date());
        if (seats.markVerified(seat) != 1) throw new IllegalStateException("席位核销状态已变化");
        seat.setStatus(1);
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("REGISTRATION"); audit.setDomainId(registrationId);
        audit.setOwnerMemberId(actor); audit.setActorMemberId(actor); audit.setActorRoleCode("CLUB_OWNER");
        audit.setAction("REGISTRATION_SEAT_REDEEM"); audit.setTargetType("REGISTRATION_SEAT"); audit.setTargetId(seat.getId());
        audit.setRelationId(activityId); audit.setRequestId("registration-seat-redeem-" + seat.getId());
        audit.setBeforeJson("{\"status\":0}"); audit.setAfterJson("{\"status\":1}"); audit.setCreateTime(seat.getVerifiedAt());
        if (audits.insertBusinessAuditEvent(audit) != 1) throw new IllegalStateException("席位核销审计保存失败");
        return receipt(seat, false);
    }

    /** G reads actual facts after locking its original parents/registrations. No money eligibility inference. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public List<RegistrationSeat> verifiedSeatsForGroup(Long topicId, Long activityId, Date cutoff) {
        if (topicId == null || topicId <= 0 || activityId == null || activityId <= 0 || cutoff == null) {
            throw new IllegalArgumentException("席位事实查询范围缺失");
        }
        if (!seats.selectUnprovenMultiGroupOrdersForUpdate(topicId, activityId, cutoff).isEmpty()) {
            throw conflict("多人整单核销缺少逐席位证明，不能推算履约费，请人工核验");
        }
        return seats.selectVerifiedForGroupForUpdate(topicId, activityId, cutoff);
    }

    private CmsRegistration lockCurrent(CmsRegistration seed) {
        if (seats.lockRegistration(seed.getId()) == null) throw denied("报名单不存在");
        // lockRegistration flushes L1; do not reuse the pre-lock snapshot or its ownership.
        CmsRegistration current = registrationService.selectCmsRegistrationById(seed.getId());
        if (current == null || !Objects.equals(seed.getMemberId(), current.getMemberId())
                || !Objects.equals(seed.getOwnerType(), current.getOwnerType()) || !Objects.equals(seed.getOwnerId(), current.getOwnerId())) {
            throw conflict("原订单归属已变化，请重新核验");
        }
        return current;
    }
    private void requireTopicTicket(CmsRegistration row, Integer seatNo) {
        if (!Long.valueOf(1L).equals(row.getOwnerType()) || row.getOwnerId() == null
                || Integer.valueOf(3).equals(row.getPurchaseKind())) throw denied("本场次只可核销主题玩家票");
        if (row.getOrderNum() == null || row.getOrderNum() <= 1 || seatNo == null || seatNo <= 0 || seatNo > row.getOrderNum()) {
            throw denied("必须明确原订单中的合法席位，不能用整单码代替多人核销");
        }
    }
    private void requirePaid(CmsRegistration row) {
        if (!Integer.valueOf(2).equals(row.getRegistrationStatus()) || !Integer.valueOf(2).equals(row.getPaymentStatus())) {
            throw denied("订单未完成支付或已退款，无法核销");
        }
    }
    private RegistrationSeat find(List<RegistrationSeat> rows, Integer number) {
        return rows.stream().filter(row -> number.equals(row.getSeatNo())).findFirst().orElse(null);
    }
    private boolean verified(RegistrationSeat row) { return Integer.valueOf(1).equals(row.getStatus()); }
    private Receipt receipt(RegistrationSeat row, boolean replayed) {
        return new Receipt(row.getId(), row.getRegistrationId(), row.getSeatNo(), row.getTopicId(), row.getActivityId(),
                row.getVerifierMemberId(), row.getVerifiedAt(), replayed);
    }
    private void requireActor(Long actor) { if (actor == null || actor <= 0) throw denied("请先登录"); }
    private ServiceException denied(String message) { return new ServiceException(message, 403); }
    private ServiceException conflict(String message) { return new ServiceException(message, 409); }
}
