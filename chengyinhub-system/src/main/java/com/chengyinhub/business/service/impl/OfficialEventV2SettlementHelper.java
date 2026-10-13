package com.chengyinhub.business.service.impl;

import java.util.Date;

import com.chengyinhub.business.domain.MemberCollectible;
import com.chengyinhub.business.domain.OfficialEventRewardIssuance;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.OfficialEventV2Mapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 每位 V2 资格成员的可回滚奖励投递保存点。 */
@Service
public class OfficialEventV2SettlementHelper {
    private static final Logger logger = LoggerFactory.getLogger(OfficialEventV2SettlementHelper.class);
    @Autowired
    private OfficialEventV2Mapper v2Mapper;
    @Autowired
    private MemberCollectibleMapper collectibleMapper;
    @Autowired
    private ISmsCouponHistoryService couponHistoryService;
    @Autowired
    private IGrowthService growthService;
    @Autowired
    private AlarmService alarmService;

    /**
     * 在结算活动行锁的事务内，将 PENDING_ISSUE 原子切换为 ISSUED 后投递；每位成员使用
     * JDBC savepoint 隔离失败。不能用 REQUIRES_NEW，否则看不到外层刚写入的 PENDING_ISSUE，
     * 会造成首轮 claim=0 后活动已结束、奖励永久卡住。
     */
    @Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
    public boolean issue(OfficialEventRewardIssuance issuance, Long couponId, Integer xp, boolean badge,
                         Long collectiveCouponId, boolean collectiveReached) {
        if (v2Mapper.claimPendingRewardIssuance(issuance.getId()) == 0) return false;
        Long memberId = issuance.getMemberId();
        Long eventId = issuance.getEventId();
        if (couponId != null && couponId > 0 && !skipDisabled(couponId, issuance)
                && !couponHistoryService.grantCouponById(memberId, couponId)) {
            alarmService.alarm("官方活动V2结算券库存不足", "event=" + eventId + " coupon=" + couponId + " member=" + memberId);
            throw new IllegalStateException("官方活动 V2 结算券发放失败");
        }
        if (xp != null && xp > 0) growthService.addExp(memberId, xp, "官方活动V2结算奖 event=" + eventId);
        if (badge) grantBadge(memberId, eventId);
        if (collectiveReached && collectiveCouponId != null && collectiveCouponId > 0
                && !skipDisabled(collectiveCouponId, issuance)
                && !couponHistoryService.grantCouponById(memberId, collectiveCouponId)) {
            alarmService.alarm("官方活动V2集体券库存不足", "event=" + eventId + " coupon=" + collectiveCouponId + " member=" + memberId);
            throw new IllegalStateException("官方活动 V2 集体券发放失败");
        }
        return true;
    }

    /** C-30:券已失效(3)/已停发(4) → 跳过发券(不算失败、不回滚),经验/徽章照常发。 */
    private boolean skipDisabled(Long couponId, OfficialEventRewardIssuance issuance) {
        if (!couponHistoryService.isCouponNotIssuable(couponId)) return false;
        logger.warn("COUPON_DISABLED_SKIP caller=officialEventV2Settlement eventId={} issuanceId={} memberId={} couponId={}",
                issuance.getEventId(), issuance.getId(), issuance.getMemberId(), couponId);
        return true;
    }

    private void grantBadge(Long memberId, Long eventId) {
        if (collectibleMapper.countOne(memberId, MemberCollectible.TYPE_EVENT_BADGE, eventId) > 0) return;
        MemberCollectible badge = new MemberCollectible();
        badge.setMemberId(memberId);
        badge.setCollType(MemberCollectible.TYPE_EVENT_BADGE);
        badge.setRefId(eventId);
        badge.setCreateTime(new Date());
        collectibleMapper.insertMemberCollectible(badge);
    }
}
