package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper;
import com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl;
import com.chengyinhub.common.constant.RewardPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TopicCompletionAwardServiceTest {
    @Test
    void redemptionTimeIsPreservedButEvidenceIdentityAlwaysComesFromBadge() {
        Fixture f = new Fixture();
        when(f.badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenAnswer(invocation -> {
            invocation.<PlayerBadge>getArgument(0).setId(55L);
            return 1;
        });
        Date redemptionTime = new Date(1_755_244_320_000L);

        f.service.grantOnce(9L, 7L, 11L, redemptionTime, RewardPolicy.FULL);

        verify(f.points).award(9L, 13L, 11L, "通关主题");
        verify(f.events).recordThemeCompletion(9L, 7L, "badge:55", redemptionTime);
    }

    @Test
    void xpFactIsPersistedOnceAndDuplicateCompletionDoesNotIssueAgain() {
        Fixture f = new Fixture();
        Date redemptionTime = new Date(1_755_244_320_000L);
        when(f.budgets.computeBudget(7L)).thenReturn(Collections.<String, Object>singletonMap("remain", 36));
        when(f.badgeMapper.insertPlayerBadge(any(PlayerBadge.class)))
                .thenReturn(1)
                .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));
        when(f.badgeMapper.selectOne(9L, "TOPIC_FINISH_7", 7L)).thenReturn(new PlayerBadge());

        assertEquals(36, f.service.grantOnce(9L, 7L, 11L, redemptionTime).getEarnedXp());
        assertFalse(f.service.grantOnce(9L, 7L, 11L, redemptionTime).isFirstCompletion());

        verify(f.growth, times(1)).addCompletionExp(9L, 36, 7L);
        verify(f.xpAwardMapper, times(1)).insert(9L, 7L, 36, redemptionTime);
    }

    @Test
    void requiredRewardFailurePropagatesInsteadOfReportingManual补发() {
        Fixture f = new Fixture();
        f.topic.setCompleteRewardCouponId(88L);
        doThrow(new IllegalStateException("coupon failed"))
                .when(f.coupons).grantOnce(7L, 9L, 88L);

        assertThrows(IllegalStateException.class,
                () -> f.service.grantOnce(9L, 7L, 11L, new Date(1000L)));

        verify(f.events, never()).recordThemeCompletion(any(), any(), any(), any());
    }

    @Test
    void xpBadgeOnlyKeepsGrowthAndBadgesButSkipsPointsAndCoupon() {
        Fixture f = new Fixture();
        when(f.budgets.computeBudget(7L)).thenReturn(Collections.<String, Object>singletonMap("remain", 36));

        f.service.grantOnce(9L, 7L, 11L, new Date(1000L), RewardPolicy.XP_BADGE_ONLY);

        verify(f.points, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        verify(f.coupons, never()).grantOnce(anyLong(), anyLong(), anyLong());
        verify(f.events, never()).recordThemeCompletion(anyLong(), anyLong(), anyString(), any(Date.class));
        verify(f.progression).onTopicComplete(9L, 7L);
        verify(f.growth).addCompletionExp(9L, 36, 7L);
    }

    // ---------------- 第二轮拍板第 4 条:通关奖励判重从「勋章插入成功」拆开

    private static final String POINTS = com.chengyinhub.business.service.impl.RedemptionRewardClawbackService.MARKER_COMPLETION_POINTS;
    private static final String XP = com.chengyinhub.business.service.impl.RedemptionRewardClawbackService.MARKER_COMPLETION_XP;
    private static final String COUPON = com.chengyinhub.business.service.impl.RedemptionRewardClawbackService.MARKER_COMPLETION_COUPON;

    /** 勋章已在的再次通关;markers = 尚未消费的冲回标记。 */
    private static Fixture repeatCompletion(String... markers) {
        Fixture f = new Fixture();
        f.topic.setCompleteRewardCouponId(88L);
        when(f.budgets.computeBudget(7L)).thenReturn(Collections.<String, Object>singletonMap("remain", 36));
        when(f.badgeMapper.insertPlayerBadge(any(PlayerBadge.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));
        when(f.badgeMapper.selectOne(9L, "TOPIC_FINISH_7", 7L)).thenReturn(new PlayerBadge());
        when(f.progressMapper.countCompletionClawbackMarkers(9L, 7L)).thenReturn(markers.length);
        when(f.progressMapper.selectCompletionClawbackMarkersForUpdate(9L, 7L)).thenReturn(java.util.Arrays.asList(markers));
        return f;
    }

    /** 三项都被冲回过的玩家再通关:积分/券/XP 各补一份、标记逐项消费、先锁会员行;勋章/成长徽章/活动证据不重复。 */
    @Test
    void clawedBackPlayerRecompletingEarnsRewardsAgainButNoSecondBadge() {
        Fixture f = repeatCompletion(POINTS, XP, COUPON);

        assertEquals(36, f.service.grantOnce(9L, 7L, 11L, new Date(1000L)).getEarnedXp());

        org.mockito.InOrder order = inOrder(f.members, f.progressMapper);
        order.verify(f.members).lockMemberById(9L);
        order.verify(f.progressMapper).selectCompletionClawbackMarkersForUpdate(9L, 7L);
        verify(f.points).award(9L, 13L, 11L, "通关主题");
        verify(f.coupons).grantOnce(7L, 9L, 88L);
        verify(f.growth).addCompletionExp(9L, 36, 7L);
        verify(f.progressMapper).deleteCompletionClawbackMarker(9L, 7L, POINTS);
        verify(f.progressMapper).deleteCompletionClawbackMarker(9L, 7L, XP);
        verify(f.progressMapper).deleteCompletionClawbackMarker(9L, 7L, COUPON);
        verify(f.progression, never()).onTopicComplete(anyLong(), anyLong());
        verify(f.events, never()).recordThemeCompletion(any(), any(), any(), any());
    }

    /** 只冲回过积分(XP/券从没发过):只补积分,不倒补从没发过的 XP 与券。 */
    @Test
    void onlyTheClawedBackRewardIsReissued() {
        Fixture f = repeatCompletion(POINTS);

        f.service.grantOnce(9L, 7L, 11L, new Date(1000L));

        verify(f.points).award(9L, 13L, 11L, "通关主题");
        verify(f.coupons, never()).grantOnce(anyLong(), anyLong(), anyLong());
        verify(f.growth, never()).addCompletionExp(anyLong(), anyInt(), anyLong());
    }

    /** 历史通关者(无冲回标记)或标记已被上一次补发消费:什么都不发,不加锁 —— 换一场再通关不许再领。 */
    @Test
    void repeatCompletionWithoutClawbackGrantsNothingRetroactively() {
        Fixture f = repeatCompletion();

        assertEquals(0, f.service.grantOnce(9L, 7L, 12L, new Date(1000L)).getEarnedXp());

        verify(f.points, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        verify(f.coupons, never()).grantOnce(anyLong(), anyLong(), anyLong());
        verify(f.growth, never()).addCompletionExp(anyLong(), anyInt(), anyLong());
        verify(f.members, never()).lockMemberById(anyLong());
    }

    /** 冲回后已重新发过一次 XP(发放事实又在了):即使 XP 标记还在也不再发第二份。 */
    @Test
    void reGrantedXpIsNotIssuedTwice() {
        Fixture f = repeatCompletion(XP);
        when(f.xpAwardMapper.selectOne(9L, 7L)).thenReturn(new com.chengyinhub.business.domain.vo.TopicCompletionAwardVO());

        f.service.grantOnce(9L, 7L, 11L, new Date(1000L));

        verify(f.growth, never()).addCompletionExp(anyLong(), anyInt(), anyLong());
        verify(f.xpAwardMapper, never()).insert(any(), any(), any(), any());
    }

    private static final class Fixture {
        private final TopicCompletionAwardServiceImpl service = new TopicCompletionAwardServiceImpl();
        private final ICmsTopicService topicService = mock(ICmsTopicService.class);
        private final PlayerBadgeMapper badgeMapper = mock(PlayerBadgeMapper.class);
        private final IPointsAwardService points = mock(IPointsAwardService.class);
        private final IPlayerProgressionService progression = mock(IPlayerProgressionService.class);
        private final ITopicCompletionCouponRewardService coupons = mock(ITopicCompletionCouponRewardService.class);
        private final IOfficialEventV2Service events = mock(IOfficialEventV2Service.class);
        private final IXpBudgetService budgets = mock(IXpBudgetService.class);
        private final IGrowthService growth = mock(IGrowthService.class);
        private final TopicCompletionXpAwardMapper xpAwardMapper = mock(TopicCompletionXpAwardMapper.class);
        private final com.chengyinhub.business.mapper.GrowthMissionProgressMapper progressMapper =
                mock(com.chengyinhub.business.mapper.GrowthMissionProgressMapper.class);
        private final IUmsMemberService members = mock(IUmsMemberService.class);
        private final CmsTopic topic = new CmsTopic();

        private Fixture() {
            ReflectionTestUtils.setField(service, "topicService", topicService);
            ReflectionTestUtils.setField(service, "playerBadgeMapper", badgeMapper);
            ReflectionTestUtils.setField(service, "pointsAwardService", points);
            ReflectionTestUtils.setField(service, "playerProgressionService", progression);
            ReflectionTestUtils.setField(service, "couponRewardService", coupons);
            ReflectionTestUtils.setField(service, "officialEventV2Service", events);
            ReflectionTestUtils.setField(service, "xpBudgetService", budgets);
            ReflectionTestUtils.setField(service, "growthService", growth);
            ReflectionTestUtils.setField(service, "xpAwardMapper", xpAwardMapper);
            ReflectionTestUtils.setField(service, "growthMissionProgressMapper", progressMapper);
            ReflectionTestUtils.setField(service, "umsMemberService", members);
            topic.setId(7L);
            topic.setName("探店日");
            when(topicService.selectCmsTopicById(7L)).thenReturn(topic);
            when(badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenReturn(1);
            when(progression.onTopicComplete(9L, 7L)).thenReturn(Collections.emptyList());
            when(budgets.computeBudget(7L)).thenReturn(Collections.emptyMap());
            when(growth.addCompletionExp(anyLong(), anyInt(), anyLong())).thenReturn(901L);
            when(xpAwardMapper.insert(any(), any(), any(), any())).thenReturn(1);
            when(xpAwardMapper.linkEarnedExp(any(), any(), any())).thenReturn(1);
        }
    }
}
