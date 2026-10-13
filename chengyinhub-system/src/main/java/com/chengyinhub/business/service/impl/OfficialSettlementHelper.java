package com.chengyinhub.business.service.impl;

import java.util.Date;

import com.chengyinhub.business.domain.EventSignup;
import com.chengyinhub.business.domain.MemberCollectible;
import com.chengyinhub.business.mapper.EventSignupMapper;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 官方活动结算发奖 —— 单个报名的独立事务单元。
 *
 * 独立成 bean(而非 OfficialEventServiceImpl 内部方法)有两个必需的理由:
 *  1) {@code REQUIRES_NEW} 逐人隔离:某个用户发奖失败只回滚这一条,不牵连同批已发成功的用户;
 *  2) 事务代理不被绕过:结算由定时任务 {@code runStatusFlow()} 自调用触发,
 *     若结算逻辑写在同类内部方法里,Spring 事务代理会因 self-invocation 失效(无事务)。
 *
 * @author cy
 * @date 2026-07-09
 */
@Service
public class OfficialSettlementHelper {

    private static final Logger logger = LoggerFactory.getLogger(OfficialSettlementHelper.class);

    @Autowired
    private EventSignupMapper signupMapper;
    @Autowired
    private MemberCollectibleMapper collectibleMapper;
    @Autowired
    private ISmsCouponHistoryService couponHistoryService;
    @Autowired
    private IGrowthService growthService;
    @Autowired
    private AlarmService alarmService;   // C3 监控:结算发奖失败告警(配 alarm.webhook 推钉钉/企微)

    /**
     * 结算单个报名(独立事务)。先原子领取(WHERE reward_claimed=0)抢到才发奖;
     * 任一发奖(发券/加经验/发徽章)抛异常都不吞 —— 让本条独立事务整体回滚,
     * reward_claimed 随之退回 0,下一轮结算/手动补结算可重试,杜绝"标记已领却没发出"的静默丢奖。
     *
     * @return true=本次真实发放;false=已被领取(并发或重跑),调用方应跳过计数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean settleOne(EventSignup s, Long eventId, Long settleCouponId, Integer settleXp,
                             boolean settleBadge, boolean collectiveReached, Long collectiveCouponId) {
        // 原子领取:并发/重跑下只有把 0→1 的那一次返回 1,其余返回 0
        if (signupMapper.claimReward(s.getId(), EventSignup.ST_REWARDED) == 0) {
            return false;
        }
        boolean qualified = s.getProgress() != null && s.getProgress() > 0;
        if (qualified) {
            // C1 契约:grantCouponById 返回 false(库存耗尽/券不存在;手动失效已由 skipDisabled 先跳过)视为发放失败,抛异常触发本条
            // 独立事务回滚(reward_claimed 退回 0),下一轮/补结算重试,杜绝"标记已领却没发出"(需运营补足 publish_count)
            if (settleCouponId != null && settleCouponId > 0
                    && !skipDisabled(settleCouponId, eventId, s)
                    && !couponHistoryService.grantCouponById(s.getMemberId(), settleCouponId)) {
                alarmService.alarm("官方结算券库存不足", "event=" + eventId + " coupon=" + settleCouponId + " member=" + s.getMemberId());
                throw new IllegalStateException("官方活动结算券发放失败(库存不足?) event=" + eventId + " coupon=" + settleCouponId);
            }
            if (settleXp != null && settleXp > 0) {
                growthService.addExp(s.getMemberId(), settleXp, "官方活动结算奖 event=" + eventId);
            }
            if (settleBadge) {
                grantBadge(s.getMemberId(), eventId);
            }
        }
        if (collectiveReached && collectiveCouponId != null && collectiveCouponId > 0
                && !skipDisabled(collectiveCouponId, eventId, s)
                && !couponHistoryService.grantCouponById(s.getMemberId(), collectiveCouponId)) {
            alarmService.alarm("官方集体奖券库存不足", "event=" + eventId + " coupon=" + collectiveCouponId + " member=" + s.getMemberId());
            throw new IllegalStateException("官方活动集体奖券发放失败(库存不足?) event=" + eventId + " coupon=" + collectiveCouponId);
        }
        return true;
    }

    /** C-30:券已失效(3)/已停发(4) → 跳过发券(不算失败、不回滚),经验/徽章照常发。 */
    private boolean skipDisabled(Long couponId, Long eventId, EventSignup s) {
        if (!couponHistoryService.isCouponNotIssuable(couponId)) return false;
        logger.warn("COUPON_DISABLED_SKIP caller=officialSettlement eventId={} signupId={} memberId={} couponId={}",
                eventId, s.getId(), s.getMemberId(), couponId);
        return true;
    }

    /** 活动徽章:同 (member,event) 已有则跳过,避免重复发。 */
    private void grantBadge(Long memberId, Long eventId) {
        if (collectibleMapper.countOne(memberId, MemberCollectible.TYPE_EVENT_BADGE, eventId) > 0) {
            return;
        }
        MemberCollectible c = new MemberCollectible();
        c.setMemberId(memberId);
        c.setCollType(MemberCollectible.TYPE_EVENT_BADGE);
        c.setRefId(eventId);
        c.setCreateTime(new Date());
        collectibleMapper.insertMemberCollectible(c);
    }
}
