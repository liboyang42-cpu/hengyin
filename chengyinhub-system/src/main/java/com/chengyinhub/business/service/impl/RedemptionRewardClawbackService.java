package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.GrowthMissionProgress;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper;
import com.chengyinhub.business.mapper.GrowthMissionProgressMapper;
import com.chengyinhub.business.mapper.MemberGrowthMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.StoreVerifyGrowthDeliveryMapper;
import com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第二轮拍板第 4 条:后台撤销核销时勾选「同时冲回因这次核销发的积分和经验」。
 *
 * <p>冲什么(只冲这次核销真正发出去的东西,勋章不收回):
 * <ul>
 *   <li>撤完这张票已无任何生效核销 ⇒ 到店积分(eventType=11)+ 到店成长经验(store_verify);</li>
 *   <li>撤完主题不再通关 ⇒ 通关积分(eventType=13)+ 通关 XP;通关券未使用则作废,已使用只记审计不追回。</li>
 * </ul>
 *
 * <p>幂等按「发放事实」:原积分行摘出判重键、原经验行改挂 registration_clawed、XP 发放事实删除、券 0→2 条件更新 ——
 * 同一笔发放第二次再也找不到,最多冲一次;摘出判重键同时让玩家真到店再核销时能重新拿到奖励。
 * 余额不够就扣到 0,差额进审计。与撤销同一事务(MANDATORY),任何一步失败整个撤销回滚。
 */
@Service
public class RedemptionRewardClawbackService {

    static final long EVENT_STORE_VERIFY = 11L;
    /** 通关奖励冲回标记(growth_mission_progress.mission_code),TopicCompletionAwardServiceImpl 按项消费。 */
    public static final String MARKER_COMPLETION_POINTS = "COMPLETION_CLAWBACK_POINTS";
    public static final String MARKER_COMPLETION_XP = "COMPLETION_CLAWBACK_XP";
    public static final String MARKER_COMPLETION_COUPON = "COMPLETION_CLAWBACK_COUPON";
    static final long EVENT_TOPIC_COMPLETE = 13L;

    @Autowired private UmsMemberPointsDetailMapper pointsDetailMapper;
    @Autowired private UmsMemberMapper umsMemberMapper;
    @Autowired private GrowthMissionProgressMapper growthMissionProgressMapper;
    @Autowired private MemberGrowthMapper memberGrowthMapper;
    @Autowired private StoreVerifyGrowthDeliveryMapper storeVerifyGrowthDeliveryMapper;
    @Autowired private TopicCompletionXpAwardMapper topicCompletionXpAwardMapper;
    @Autowired private CmsTopicCompletionCouponRewardMapper completionCouponRewardMapper;
    @Autowired private SmsCouponHistoryMapper smsCouponHistoryMapper;
    @Autowired private BusinessAuditEventMapper businessAuditEventMapper;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void clawback(Redemption r, CmsRegistration registration, boolean registrationNoLongerVerified,
                         boolean completionLost, String reason, Long operatorUserId) {
        Long memberId = registration.getMemberId();
        // 锁序与发分路径一致:先锁会员行,再锁/改流水行,避免与并发发分互相死锁
        umsMemberMapper.lockMemberById(memberId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("registrationNoLongerVerified", registrationNoLongerVerified);
        detail.put("completionLost", completionLost);
        detail.put("badge", "KEPT");
        List<Map<String, Object>> points = new ArrayList<>();
        detail.put("points", points);

        if (registrationNoLongerVerified) {
            Map<String, Object> p = clawPoints(memberId, EVENT_STORE_VERIFY, registration.getId(), "撤销核销冲回:到店核销", "redeem");
            if (p != null) points.add(p);
            detail.put("storeVerifyExp", clawStoreVerifyExp(memberId, registration.getId()));
            storeVerifyGrowthDeliveryMapper.deleteByRegistration(registration.getId());
        }
        if (completionLost) {
            // 通关积分的 eventId 与发放侧一致:grantOnce(..., pointsSourceId = registration.ownerId)
            Map<String, Object> p = clawPoints(memberId, EVENT_TOPIC_COMPLETE, registration.getOwnerId(), "撤销核销冲回:通关主题", "redeem");
            if (p != null) {
                points.add(p);
                markCompletionClawback(memberId, r.getTopicId(), MARKER_COMPLETION_POINTS);
            }
            Map<String, Object> xp = clawCompletionXp(memberId, r.getTopicId());
            detail.put("completionXp", xp);
            if (xp != null) markCompletionClawback(memberId, r.getTopicId(), MARKER_COMPLETION_XP);
            Map<String, Object> coupon = voidCompletionCoupon(memberId, r.getTopicId(), r.getId());
            detail.put("completionCoupon", coupon);
            if (coupon != null && "VOIDED".equals(coupon.get("result"))) {
                markCompletionClawback(memberId, r.getTopicId(), MARKER_COMPLETION_COUPON);
            }
        }
        writeAudit(r, detail, reason, operatorUserId);
    }

    /**
     * 冲回一笔积分发放(调用方须已锁会员行):摘判重键 → 扣到 0 为止 → 记负向流水,返回请求/实扣。
     * keyTag 区分冲回来源(redeem=撤销核销,refund=FX-10 退款收回首评),写进两处业务键。
     */
    public Map<String, Object> clawPoints(Long memberId, long eventType, Long eventId, String changeReason, String keyTag) {
        if (eventId == null) return null;
        UmsMemberPointsDetail grant = pointsDetailMapper.selectGrantForClawback(memberId, eventType, eventId);
        if (grant == null || grant.getChangePoints() == null || grant.getChangePoints() <= 0) return null;
        if (pointsDetailMapper.detachGrant(grant.getId(), keyTag + "-clawed:" + grant.getId()) != 1) {
            throw new IllegalStateException("积分发放行冲回标记失败 id=" + grant.getId());
        }
        long requested = grant.getChangePoints();
        com.chengyinhub.business.domain.UmsMember member = umsMemberMapper.selectUmsMemberById(memberId);
        long before = member == null || member.getPoint() == null ? 0L : Math.max(0L, member.getPoint());
        long deducted = Math.min(requested, before);
        if (deducted > 0 && umsMemberMapper.deductPoints(memberId, (int) deducted) != 1) {
            throw new IllegalStateException("冲回积分扣减失败 memberId=" + memberId);
        }
        if (deducted == 0) return amounts(eventType, requested, 0, grant.getId());   // 余额 0:不写 0 分流水,差额进审计
        UmsMemberPointsDetail negative = new UmsMemberPointsDetail();
        negative.setMemberId(memberId);
        negative.setCurrentPoints(before);
        negative.setAfterPoints(before - deducted);
        negative.setChangePoints(deducted);
        negative.setChangeReason(changeReason);
        negative.setEventType(eventType);
        negative.setRewardBusinessKey(keyTag + "-clawback:" + grant.getId());
        negative.setChangeType(2L);
        negative.setDelFlag(0);
        negative.setCreateTime(new Date());
        if (pointsDetailMapper.insertUmsMemberPointsDetail(negative) != 1) {
            throw new IllegalStateException("冲回积分流水写入失败 memberId=" + memberId);
        }
        return amounts(eventType, requested, deducted, grant.getId());
    }

    private Map<String, Object> clawStoreVerifyExp(Long memberId, Long registrationId) {
        List<GrowthMissionProgress> awards =
                growthMissionProgressMapper.selectRegistrationAwardsForUpdate(memberId, registrationId);
        long requested = 0;
        for (GrowthMissionProgress award : awards) {
            if (growthMissionProgressMapper.markRegistrationAwardClawed(award.getId()) != 1) {
                throw new IllegalStateException("成长经验发放行冲回标记失败 id=" + award.getId());
            }
            requested += Math.max(0, award.getRewardExp() == null ? 0 : award.getRewardExp());
        }
        int deducted = deductExp(memberId, requested);
        int remaining = deducted;
        for (GrowthMissionProgress award : awards) {
            int portion = Math.min(Math.max(0, award.getRewardExp() == null ? 0 : award.getRewardExp()), remaining);
            remaining -= portion;
            if (portion == 0) continue;
            // 负向经验流水:同一张表,独立 biz_type,日期跟原发放日走(周榜按发放那周扣回)
            GrowthMissionProgress negative = new GrowthMissionProgress();
            negative.setMemberId(memberId);
            negative.setMissionCode(award.getMissionCode());
            negative.setBizType("registration_clawback");
            negative.setBizId(award.getId());
            negative.setProgressDate(award.getProgressDate());
            negative.setCompleteCount(0);
            negative.setRewardExp(-portion);
            negative.setCreateTime(new Date());
            negative.setUpdateTime(new Date());
            if (growthMissionProgressMapper.insertMissionProgress(negative) != 1) {
                throw new IllegalStateException("冲回经验流水写入失败 progressId=" + award.getId());
            }
        }
        return amounts(null, requested, deducted, null);
    }

    private Map<String, Object> clawCompletionXp(Long memberId, Long topicId) {
        if (topicId == null) return null;
        Integer xp = topicCompletionXpAwardMapper.selectXpForUpdate(memberId, topicId);
        if (xp == null || xp <= 0) return null;
        topicCompletionXpAwardMapper.deleteAward(memberId, topicId);
        return amounts(null, xp, deductExp(memberId, xp), null);
    }

    /**
     * 通关奖励冲回标记:只在这一项奖励**确实被冲回**时写(没发过的不写,防倒补历史通关者);reward_exp=0 不改周榜分数。
     * TopicCompletionAwardServiceImpl 对非首次通关只补发有标记的那几项,补发完即删标记。
     * 同日同主题同项再冲一次撞唯一键(insert ignore 返 0)= 标记已在,不算失败。
     */
    private void markCompletionClawback(Long memberId, Long topicId, String missionCode) {
        if (topicId == null) return;
        GrowthMissionProgress marker = new GrowthMissionProgress();
        marker.setMemberId(memberId);
        marker.setMissionCode(missionCode);
        marker.setBizType("topic_completion_clawback");
        marker.setBizId(topicId);
        marker.setProgressDate(new Date());
        marker.setCompleteCount(0);
        marker.setRewardExp(0);
        marker.setCreateTime(new Date());
        marker.setUpdateTime(new Date());
        growthMissionProgressMapper.insertMissionProgress(marker);
    }

    private int deductExp(Long memberId, long requested) {
        if (requested <= 0) return 0;
        Integer current = memberGrowthMapper.selectExpForUpdate(memberId);
        int deducted = (int) Math.min(requested, current == null ? 0 : Math.max(0, current));
        if (deducted > 0 && memberGrowthMapper.deductExp(memberId, deducted) != 1) {
            throw new IllegalStateException("冲回经验扣减失败 memberId=" + memberId);
        }
        return deducted;
    }

    private Map<String, Object> voidCompletionCoupon(Long memberId, Long topicId, Long redemptionId) {
        if (topicId == null) return null;
        Long couponId = completionCouponRewardMapper.selectCouponId(topicId, memberId);
        if (couponId == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("couponId", couponId);
        Long historyId = smsCouponHistoryMapper.selectCompletionRewardHistoryId(memberId, topicId);
        SmsCouponHistory history = historyId == null ? null
                : smsCouponHistoryMapper.selectSmsCouponHistoryByIdForUpdate(historyId);
        if (history == null) {
            out.put("result", "NOT_FOUND");
            return out;
        }
        out.put("historyId", history.getId());
        if (smsCouponHistoryMapper.voidUnusedCoupon(history.getId(), "撤销核销冲回作废 redemption#" + redemptionId) == 1) {
            // 作废了才删发放日志:重新通关能再发一张;已使用的券不删日志,再通关不再多给
            completionCouponRewardMapper.deleteGrant(topicId, memberId);
            out.put("result", "VOIDED");
        } else if (Integer.valueOf(1).equals(history.getUseStatus())) {
            out.put("result", "USED_NOT_RECOVERED");
        } else {
            out.put("result", "ALREADY_INACTIVE");
        }
        return out;
    }

    private static Map<String, Object> amounts(Long eventType, long requested, long deducted, Long grantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (eventType != null) m.put("eventType", eventType);
        if (grantId != null) m.put("grantDetailId", grantId);
        m.put("requested", requested);
        m.put("deducted", deducted);
        m.put("shortfall", requested - deducted);
        return m;
    }

    private void writeAudit(Redemption r, Map<String, Object> detail, String reason, Long operatorUserId) {
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("REGISTRATION");
        audit.setDomainId(r.getRegistrationId());
        audit.setOwnerMemberId(r.getMerchantId());
        audit.setActorType(BusinessAuditEvent.ACTOR_TYPE_SYS_USER);
        audit.setActorMemberId(operatorUserId);
        audit.setActorRoleCode("SYS_USER");
        audit.setAction("REDEMPTION_REWARD_CLAWBACK");
        audit.setTargetType("REDEMPTION");
        audit.setTargetId(r.getId());
        audit.setRelationId(r.getChapterId());
        audit.setAfterJson(JSON.toJSONString(detail));
        audit.setReason(reason);
        audit.setRequestId("redeem-clawback-" + r.getId());
        audit.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new IllegalStateException("冲回审计写入失败");
        }
    }
}
