package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.constant.RewardPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

@Service
public class TopicCompletionAwardServiceImpl implements ITopicCompletionAwardService {
    // Topic -> route -> completion award also exists; resolve this read edge after bean initialization.
    @Autowired
    @org.springframework.context.annotation.Lazy
    private ICmsTopicService topicService;
    @Autowired private PlayerBadgeMapper playerBadgeMapper;
    @Autowired private IPointsAwardService pointsAwardService;
    @Autowired private IPlayerProgressionService playerProgressionService;
    @Autowired private ITopicCompletionCouponRewardService couponRewardService;
    @Autowired private IOfficialEventV2Service officialEventV2Service;
    @Autowired private IXpBudgetService xpBudgetService;
    @Autowired private IGrowthService growthService;
    @Autowired private TopicCompletionXpAwardMapper xpAwardMapper;
    @Autowired private com.chengyinhub.business.mapper.GrowthMissionProgressMapper growthMissionProgressMapper;
    @Autowired private IUmsMemberService umsMemberService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicCompletionAwardVO grantOnce(Long memberId, Long topicId, Long pointsSourceId, Date occurredAt) {
        return grantOnce(memberId, topicId, pointsSourceId, occurredAt, RewardPolicy.FULL);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicCompletionAwardVO grantOnce(Long memberId, Long topicId, Long pointsSourceId, Date occurredAt,
            RewardPolicy rewardPolicy) {
        // 同一会员的通关发奖按会员行串行:并发的重复通关(玩家最后一站打卡与商家扫码同时到、
        // 连点或弱网重试)在这里排队,等先到的那笔提交后撞通关勋章唯一键,按「已通关」返回。
        // 不先锁的话,它会在未提交的勋章行上等待,与先到那笔随后写入的里程/通关徽章互相等待,
        // 被 InnoDB 选为死锁牺牲者整单回滚(F3 真库 PlayerGrowthConcurrencyMySqlIT)。
        // 锁序:打卡路径在外层已先锁会员行(节点打卡积分)再写成长;商家核销先锁报名/台账行再进这里;两边一致。
        umsMemberService.lockMemberById(memberId);
        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        if (topic == null) throw new IllegalStateException("主题不存在 topicId=" + topicId);
        Date factTime = occurredAt == null ? new Date() : occurredAt;
        String code = "TOPIC_FINISH_" + topicId;
        PlayerBadge badge = new PlayerBadge();
        badge.setMemberId(memberId);
        badge.setBadgeCode(code);
        badge.setBadgeName(StringUtils.isNotBlank(topic.getFinishMedalName())
                ? topic.getFinishMedalName() : topic.getName() + " · 通关");
        badge.setIconUrl(topic.getFinishMedalImg());
        badge.setSourceType(2);
        badge.setSourceId(topicId);
        badge.setObtainTime(factTime);
        badge.setCreateTime(factTime);
        badge.setDelFlag(0);

        TopicCompletionAwardVO result = new TopicCompletionAwardVO();
        boolean firstCompletion;
        try {
            int rows = playerBadgeMapper.insertPlayerBadge(badge);
            if (rows != 1) throw new IllegalStateException("通关勋章插入异常 rows=" + rows);
            firstCompletion = true;
        } catch (DuplicateKeyException duplicate) {
            // 当前读:商家核销路径按 DEFAULT 隔离级别进来、此前已有普通读,REPEATABLE READ 下的旧快照
            // 看不到并发请求刚提交的勋章;普通读回查会误把「已通关」当成别的唯一键冲突抛出去。
            if (playerBadgeMapper.selectOneForUpdate(memberId, code, topicId) == null) throw duplicate;
            firstCompletion = false;
        }

        // 第二轮拍板第 4 条:勋章只管「勋章」与首次通关的事实(成长徽章/官方活动证据);
        // 通关积分、通关券、通关 XP 各自按**发放事实**判重,不再挂在勋章插入成功上 ——
        // 后台撤销核销冲回会把对应发放事实摘掉(积分行摘判重键、券日志删、XP 事实删)并留冲回标记,
        // 玩家重新通关时这三样能再发一份,而勋章不收回也不重复发。
        // ⚠️ 非首次通关只补发「确实被冲回过」的那几项,补发即消费标记:
        //    · 历史通关者(早于券日志/XP 事实表上线)没有标记 ⇒ 不倒补;
        //    · 积分判重键是 (member,13,本场 id),标记不消费的话换一场再通关会再领一次 ⇒ 补发后立刻删标记;
        //    · 并发:会员行已在方法开头锁住,再加锁读标记,两个同时到的通关请求只有一个补发。
        java.util.Set<String> reopened = java.util.Collections.emptySet();
        if (!firstCompletion && growthMissionProgressMapper.countCompletionClawbackMarkers(memberId, topicId) > 0) {
            reopened = new java.util.HashSet<>(
                    growthMissionProgressMapper.selectCompletionClawbackMarkersForUpdate(memberId, topicId));
        }
        boolean pointsOpen = firstCompletion || consume(reopened, memberId, topicId, RedemptionRewardClawbackService.MARKER_COMPLETION_POINTS);
        boolean couponOpen = firstCompletion || consume(reopened, memberId, topicId, RedemptionRewardClawbackService.MARKER_COMPLETION_COUPON);
        boolean xpOpen = firstCompletion || consume(reopened, memberId, topicId, RedemptionRewardClawbackService.MARKER_COMPLETION_XP);
        if (firstCompletion) {
            result.setFirstCompletion(true);
            result.getBadges().add(badge);
            result.getBadges().addAll(new ArrayList<>(playerProgressionService.onTopicComplete(memberId, topicId)));
        }
        if (rewardPolicy == RewardPolicy.FULL) {
            if (pointsOpen) {
                pointsAwardService.award(memberId, 13L, pointsSourceId, "通关主题");   // 自身按 (member,13,eventId) 判重
            }
            if (couponOpen && topic.getCompleteRewardCouponId() != null && topic.getCompleteRewardCouponId() > 0) {
                couponRewardService.grantOnce(topicId, memberId, topic.getCompleteRewardCouponId()); // 日志 uk 判重
            }
            if (firstCompletion) {
                officialEventV2Service.recordThemeCompletion(memberId, topicId, "badge:" + badge.getId(), factTime);
            }
        }
        if (xpOpen && xpAwardMapper.selectOne(memberId, topicId) == null) {   // XP 发放事实判重
            Map<String, Object> budget = xpBudgetService.computeBudget(topicId);
            int xp = budget.get("remain") instanceof Number ? ((Number) budget.get("remain")).intValue() : 0;
            if (xp > 0) {
                Long earnedExpId = growthService.addCompletionExp(memberId, xp, topicId);
                if (earnedExpId == null || earnedExpId <= 0
                        || xpAwardMapper.insert(memberId, topicId, xp, factTime) != 1
                        || xpAwardMapper.linkEarnedExp(memberId, topicId, earnedExpId) != 1) {
                    throw new IllegalStateException("通关 XP 发放事实写入异常");
                }
                result.setEarnedXp(xp);
                result.setEarnedXpAt(factTime);
            }
        }
        return result;
    }

    /** 有这一项的冲回标记 ⇒ 删掉它(本次补发即消费)并放行;没有 ⇒ 不放行。 */
    private boolean consume(java.util.Set<String> reopened, Long memberId, Long topicId, String marker) {
        if (!reopened.contains(marker)) return false;
        growthMissionProgressMapper.deleteCompletionClawbackMarker(memberId, topicId, marker);
        return true;
    }
}
