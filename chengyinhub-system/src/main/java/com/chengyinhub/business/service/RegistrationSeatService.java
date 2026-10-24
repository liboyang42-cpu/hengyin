package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/** Seat facts extend existing registration QR/group verification; no amount or settlement calculation. */
@Service
public class RegistrationSeatService {
    @Autowired private RegistrationSeatMapper seats;
    @Autowired private RegistrationSeatSettlementReader settlementReader;
    @Autowired private CmsRegistrationMapper registrations;
    @Autowired private CmsTopicMapper topics;
    @Autowired private CmsActivityMapper activities;
    @Autowired private ICmsRegistrationService registrationService;
    @Autowired private IClubMemberService clubs;
    @Autowired private BusinessAuditEventMapper audits;
    @Autowired private com.chengyinhub.business.access.merchant.IMerchantAccessService merchants;

    public record Receipt(Long seatId, Long registrationId, Integer seatNo, Long topicId,
                          Long activityId, Long verifierMemberId, Date verifiedAt, boolean replayed,
                          boolean registrationFirstTime) {
        public Receipt(Long seatId, Long registrationId, Integer seatNo, Long topicId, Long activityId,
                       Long verifierMemberId, Date verifiedAt, boolean replayed) {
            this(seatId, registrationId, seatNo, topicId, activityId, verifierMemberId, verifiedAt, replayed, false);
        }
    }

    /** Uses the existing v1 HMAC type field, not a second token format or static-code namespace. */
    public static String codeType(int seatNo) {
        if (seatNo <= 0) throw new IllegalArgumentException("席位标识无效");
        return codeType(1L, seatNo);
    }
    public static String codeType(Long ownerType, int seatNo) {
        if (seatNo <= 0 || !(Long.valueOf(1L).equals(ownerType) || Long.valueOf(2L).equals(ownerType)))
            throw new IllegalArgumentException("席位所属类型无效");
        return (Long.valueOf(2L).equals(ownerType) ? "activity_seat_" : "topic_seat_") + seatNo;
    }
    public static Integer seatNumber(String signedType) {
        if (signedType == null || !signedType.matches("(?:topic|activity)_seat_[1-9][0-9]{0,9}")) return null;
        try { int value = Integer.parseInt(signedType.substring(signedType.lastIndexOf('_') + 1)); return value > 0 ? value : null; }
        catch (NumberFormatException invalid) { return null; }
    }

    /** Original club activity verification window, shared by the existing HTTP gate and locked seat admission. */
    public static String activityVerificationTooEarly(CmsActivity activity, Date now) {
        if (activity == null || activity.getClubId() == null || activity.getClubId() <= 0
                || activity.getStartDate() == null || now == null) return null;
        return activity.getStartDate().getTime() - now.getTime() > 60L * 60L * 1000L
                ? "俱乐部场次开始前60分钟内才可核销" : null;
    }

    /** Lazy identity creation, not fulfillment. Never expands historical verification into seats. */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public RegistrationSeat prepareOwnedSeat(Long registrationId, Long actor, Integer seatNo) {
        requireActor(actor);
        CmsRegistration seed = registrationService.selectCmsRegistrationById(registrationId);
        if (seed == null || !actor.equals(seed.getMemberId())) throw denied("无权操作该报名单");
        requireSeatTicket(seed, seatNo);
        Long topicId = topicOf(seed);
        if (topicId != null && topics.selectCmsTopicByIdForUpdate(topicId) == null) throw denied("主题不存在");
        if (Long.valueOf(2L).equals(seed.getOwnerType())) {
            CmsActivity activity = activities.selectCmsActivityByIdForUpdate(seed.getOwnerId());
            requireActivity(activity);
            if (!Objects.equals(topicId, activity.getTopicId())) throw conflict("原活动归属已变化，请重新核验");
        }
        CmsRegistration current = lockCurrent(seed);
        if (!actor.equals(current.getMemberId())) throw denied("无权操作该报名单");
        requireSeatTicket(current, seatNo);
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

    /** Existing group permission for topic seats. Activity seats retain the separate original activity permission. */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Receipt redeemGroupSeat(Long registrationId, Integer seatNo, Long activityId, Long actor) {
        return redeemSeat(registrationId, seatNo, activityId, actor, false, true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Receipt redeemActivitySeat(Long registrationId, Integer seatNo, Long actor) {
        return redeemSeat(registrationId, seatNo, null, actor, true, true);
    }

    /** Verifier-only read: buyer self-read permission must never become scan acknowledgement permission. */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Receipt verifierSeatReceipt(Long registrationId, Integer seatNo, Long activityId, Long actor, boolean activityTicket) {
        return redeemSeat(registrationId, seatNo, activityId, actor, activityTicket, false);
    }

    /** Current locking read only: no initialization, aggregate verification, CAS or audit on this path. */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Receipt seatReceipt(Long registrationId, Integer seatNo, Long activityId, Long actor) {
        requireActor(actor);
        CmsRegistration seed = registrationService.selectCmsRegistrationById(registrationId);
        if (seed == null) throw denied("报名单不存在或无权读取");
        requireSeatTicket(seed, seatNo);
        if (actor.equals(seed.getMemberId())) {
            CmsRegistration current = lockCurrent(seed);
            if (!actor.equals(current.getMemberId())) throw denied("无权读取原席位");
            RegistrationSeat selected = find(seats.selectByRegistrationForUpdate(registrationId), seatNo);
            if (selected != null && verified(selected) && activityId != null && !activityId.equals(selected.getActivityId()))
                throw conflict("原席位不属于当前场次");
            return selected != null && verified(selected) ? receipt(selected, true) : null;
        }
        return redeemSeat(registrationId, seatNo, activityId, actor, Long.valueOf(2L).equals(seed.getOwnerType()), false);
    }

    private Receipt redeemSeat(Long registrationId, Integer seatNo, Long requestedActivityId, Long actor,
                               boolean activityTicket, boolean execute) {
        requireActor(actor);
        if (registrationId == null) throw denied("核销码无效");
        CmsRegistration seed = registrationService.selectCmsRegistrationById(registrationId);
        if (seed == null) throw denied("核销码无效");
        requireSeatTicket(seed, seatNo);
        if (!(activityTicket ? Long.valueOf(2L) : Long.valueOf(1L)).equals(seed.getOwnerType()))
            throw denied("核销码类型与原订单不匹配");
        Long activityId = activityTicket ? seed.getOwnerId() : requestedActivityId;
        if (activityTicket && requestedActivityId != null && !requestedActivityId.equals(activityId))
            throw denied("活动席位不属于当前带队场次");
        if (activityId == null || activityId <= 0) throw denied("请选择带队场次后再核销");
        Long topicId = topicOf(seed);
        CmsTopic topic = topicId == null ? null : topics.selectCmsTopicByIdForUpdate(topicId);
        if (topicId != null && topic == null) throw denied("主题不存在");
        CmsActivity activity = activities.selectCmsActivityByIdForUpdate(activityId);
        if (activity == null) throw denied("活动不存在");
        if (!Objects.equals(topicId, activity.getTopicId())) throw denied("订单与当前场次主题不匹配");
        Long verifierOwner = actor;
        String actorRole = "CLUB_OWNER";
        if (activityTicket) {
            verifierOwner = activity.getMemberId();
            if (verifierOwner == null) throw denied("活动主办方数据异常");
            actorRole = "ACTIVITY_OWNER";
            if (!verifierOwner.equals(actor)) {
                var access = merchants.require(actor, com.chengyinhub.business.access.merchant.MerchantPermission.VERIFY);
                if (!verifierOwner.equals(access.getOwnerMemberId())) throw denied("该店无权核销本场玩家票");
                actorRole = access.getRoleCode();
            }
        } else {
            if (activity.getClubId() == null || activity.getClubId() <= 0 || topic == null) throw denied("该带队场次不可核销");
            Long clubOwner = seats.lockClubOwner(activity.getClubId());
            if (!actor.equals(clubOwner) || !clubs.isTopicOwner(topic.getId(), activity.getClubId(), actor))
                throw denied("仅当前承接俱乐部主理人可核销玩家票");
        }
        CmsRegistration current = lockCurrent(seed);
        requireSeatTicket(current, seatNo);
        List<RegistrationSeat> rows = seats.selectByRegistrationForUpdate(registrationId);
        RegistrationSeat seat = find(rows, seatNo);
        if (seat == null) {
            if (!execute) return null;
            throw conflict("席位身份不存在，请由购票人重新出示原席位码");
        }
        CoopGroupMemberVerify binding = activityTicket ? null : seats.selectGroupForUpdate(registrationId);
        if (binding != null && (!topic.getId().equals(binding.getTopicId()) || !activityId.equals(binding.getActivityId()))) {
            throw conflict("原订单已归属另一带队场次");
        }
        if (verified(seat)) {
            if ((!activityTicket && binding == null) || !Objects.equals(topicId, seat.getTopicId()) || !activityId.equals(seat.getActivityId())
                    || seat.getVerifiedAt() == null || seat.getVerifierMemberId() == null) {
                throw conflict("原席位核销事实不完整，请联系人工核验");
            }
            return receipt(seat, true);
        }
        if (!execute) return null; // Absence of a terminal fact is UNKNOWN, never permission to replace the intent.
        // Identity/read scope precedes replay; current payment/settlement gates apply only to a new seat effect.
        requireActivity(activity);
        requirePaid(current);
        if (activityTicket) {
            String tooEarly = activityVerificationTooEarly(activity, new Date());
            if (tooEarly != null) throw conflict(tooEarly);
        }
        if ((topic != null && Integer.valueOf(1).equals(topic.getIsSettlement())) || Integer.valueOf(1).equals(activity.getIsSettlement())) {
            throw conflict("主题或场次已结算，不能新增核销事实");
        }
        if (registrations.hasActiveManualRefundCaseForUpdate(registrationId)) throw conflict("订单正在人工退款处理中，暂不可核销");
        boolean anyVerified = rows.stream().anyMatch(this::verified);
        boolean registrationFirstTime = false;
        if (activityTicket) {
            if (!anyVerified) {
                if (Integer.valueOf(1).equals(current.getVerificationStatus()))
                    throw conflict("历史整单核销缺少逐席位证明，请联系人工核验");
                // Original activity verifier/payee/actor/role; no quantity multiplier and no second accrual.
                if (!registrationService.verifyAndSettle(current, verifierOwner, verifierOwner, null, actor, actorRole))
                    throw conflict("原核销状态已变化，请联系人工核验");
                registrationFirstTime = true;
            } else if (!Integer.valueOf(1).equals(current.getVerificationStatus())) {
                throw conflict("原活动核销状态与席位事实不一致，请联系人工核验");
            }
        } else if (binding == null) {
            if (anyVerified || Integer.valueOf(1).equals(current.getVerificationStatus())) {
                throw conflict("历史整单核销缺少逐席位证明，请联系人工核验");
            }
            if (!registrationService.verifyAndSettleForGroupActivity(current, actor, activityId)) {
                throw conflict("原核销状态已变化，请联系人工核验");
            }
            registrationFirstTime = true;
            binding = seats.selectGroupForUpdate(registrationId);
            if (binding == null || !topicId.equals(binding.getTopicId()) || !activityId.equals(binding.getActivityId())) {
                throw new IllegalStateException("原团核销归属未保存");
            }
        } else if (!anyVerified || !Integer.valueOf(1).equals(current.getVerificationStatus())) {
            throw conflict("原团核销缺少逐席位证明，请联系人工核验");
        }
        seat.setTopicId(topicId); seat.setActivityId(activityId); seat.setVerifierMemberId(verifierOwner); seat.setVerifiedAt(new Date());
        if (seats.markVerified(seat) != 1) throw new IllegalStateException("席位核销状态已变化");
        seat.setStatus(1);
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("REGISTRATION"); audit.setDomainId(registrationId);
        audit.setOwnerMemberId(verifierOwner); audit.setActorMemberId(actor); audit.setActorRoleCode(actorRole);
        audit.setAction("REGISTRATION_SEAT_REDEEM"); audit.setTargetType("REGISTRATION_SEAT"); audit.setTargetId(seat.getId());
        audit.setRelationId(activityId); audit.setRequestId("registration-seat-redeem-" + seat.getId());
        audit.setBeforeJson("{\"status\":0}"); audit.setAfterJson("{\"status\":1}"); audit.setCreateTime(seat.getVerifiedAt());
        if (audits.insertBusinessAuditEvent(audit) != 1) throw new IllegalStateException("席位核销审计保存失败");
        return new Receipt(seat.getId(), seat.getRegistrationId(), seat.getSeatNo(), seat.getTopicId(), seat.getActivityId(),
                seat.getVerifierMemberId(), seat.getVerifiedAt(), false, registrationFirstTime);
    }

    /** Compatibility entry; settlement consumers depend on the mapper-only reader directly. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public List<RegistrationSeat> verifiedSeatsForGroup(Long topicId, Long activityId, Date cutoff) {
        return settlementReader.verifiedSeatsForGroup(topicId, activityId, cutoff);
    }

    private CmsRegistration lockCurrent(CmsRegistration seed) {
        if (seats.lockRegistration(seed.getId()) == null) throw denied("报名单不存在");
        // Flushing L1 does not reset an existing MySQL RR snapshot. Return the complete row
        // from a current locking read, never an ordinary SELECT after the ID lock.
        CmsRegistration current = registrations.selectCmsRegistrationByIdForUpdate(seed.getId());
        if (current == null || !Objects.equals(seed.getMemberId(), current.getMemberId())
                || !Objects.equals(seed.getOwnerType(), current.getOwnerType()) || !Objects.equals(seed.getOwnerId(), current.getOwnerId())) {
            throw conflict("原订单归属已变化，请重新核验");
        }
        return current;
    }
    private Long topicOf(CmsRegistration row) {
        if (Long.valueOf(1L).equals(row.getOwnerType())) return row.getOwnerId();
        CmsActivity activity = activities.selectCmsActivityById(row.getOwnerId());
        if (activity == null) throw denied("活动不存在");
        return activity.getTopicId(); // Standalone activities have no topic; never forge a topic ID.
    }
    private void requireActivity(CmsActivity activity) {
        if (activity == null || (activity.getDelFlag() != null && activity.getDelFlag() != 0) || activity.getCancelTime() != null)
            throw denied("活动已取消、删除或不存在，无法核销");
    }
    private void requireSeatTicket(CmsRegistration row, Integer seatNo) {
        if (!(Long.valueOf(1L).equals(row.getOwnerType()) || Long.valueOf(2L).equals(row.getOwnerType())) || row.getOwnerId() == null
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
        if (!verified(row) || row.getId() == null || row.getId() <= 0 || row.getRegistrationId() == null || row.getRegistrationId() <= 0
                || row.getSeatNo() == null || row.getSeatNo() <= 0 || row.getActivityId() == null || row.getActivityId() <= 0
                || row.getVerifierMemberId() == null || row.getVerifierMemberId() <= 0 || row.getVerifiedAt() == null)
            throw conflict("原席位核销事实不完整，请联系人工核验");
        return new Receipt(row.getId(), row.getRegistrationId(), row.getSeatNo(), row.getTopicId(), row.getActivityId(),
                row.getVerifierMemberId(), row.getVerifiedAt(), replayed);
    }
    private void requireActor(Long actor) { if (actor == null || actor <= 0) throw denied("请先登录"); }
    private ServiceException denied(String message) { return new ServiceException(message, 403); }
    private ServiceException conflict(String message) { return new ServiceException(message, 409); }
}
