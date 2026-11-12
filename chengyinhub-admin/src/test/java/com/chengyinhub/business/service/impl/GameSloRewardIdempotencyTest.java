package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.mapper.GrowthMissionProgressMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.ITopicCompletionCouponRewardService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.common.observability.GameSloEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OPS-01 完局发奖：唯一键冲突只有在「读不到已存在发放事实」时才算幂等冲突失败；
 * 已经发过的正常重放不得计成失败，也不得改变原返回值。
 */
class GameSloRewardIdempotencyTest
{
    private static final Long MEMBER = 31L;
    private static final Long TOPIC = 32L;
    private static final Long ACTIVITY = 33L;

    private PlayerBadgeMapper badgeMapper;
    private TopicCompletionAwardServiceImpl service;

    @BeforeEach
    void setUp()
    {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        badgeMapper = mock(PlayerBadgeMapper.class);
        service = new TopicCompletionAwardServiceImpl();
        ReflectionTestUtils.setField(service, "topicService", topicService);
        ReflectionTestUtils.setField(service, "playerBadgeMapper", badgeMapper);
        ReflectionTestUtils.setField(service, "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(service, "playerProgressionService", mock(IPlayerProgressionService.class));
        ReflectionTestUtils.setField(service, "couponRewardService", mock(ITopicCompletionCouponRewardService.class));
        ReflectionTestUtils.setField(service, "officialEventV2Service", mock(IOfficialEventV2Service.class));
        ReflectionTestUtils.setField(service, "xpBudgetService", mock(IXpBudgetService.class));
        ReflectionTestUtils.setField(service, "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(service, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(service, "growthMissionProgressMapper", mock(GrowthMissionProgressMapper.class));
        ReflectionTestUtils.setField(service, "xpAwardMapper", mock(TopicCompletionXpAwardMapper.class));

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setName("通关幂等主题");
        topic.setFinishMedalName("通关");
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
    }

    @Test
    void conflictWithoutExistingAwardIsCountedAsIdempotentFailure()
    {
        when(badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenThrow(new DuplicateKeyException("uk_member_badge"));
        when(badgeMapper.selectOne(MEMBER, "TOPIC_FINISH_" + TOPIC, TOPIC)).thenReturn(null);

        long before = GameSloEvents.rewardIdempotentConflicts();
        assertThrows(DuplicateKeyException.class,
                () -> service.grantOnce(MEMBER, TOPIC, ACTIVITY, new Date()));
        assertEquals(before + 1, GameSloEvents.rewardIdempotentConflicts(),
                "冲突且读不到发放事实 ⇒ 本次发奖失败，必须计数");
    }

    @Test
    void normalReplayOfAnAlreadyGrantedRewardIsNotAFailure()
    {
        PlayerBadge stored = new PlayerBadge();
        stored.setId(501L);
        stored.setMemberId(MEMBER);
        stored.setBadgeCode("TOPIC_FINISH_" + TOPIC);
        when(badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenThrow(new DuplicateKeyException("uk_member_badge"));
        when(badgeMapper.selectOne(MEMBER, "TOPIC_FINISH_" + TOPIC, TOPIC)).thenReturn(stored);
        GrowthMissionProgressMapper markers = (GrowthMissionProgressMapper)
                ReflectionTestUtils.getField(service, "growthMissionProgressMapper");
        when(markers.countCompletionClawbackMarkers(anyLong(), anyLong())).thenReturn(0);

        long before = GameSloEvents.rewardIdempotentConflicts();
        TopicCompletionAwardVO award = service.grantOnce(MEMBER, TOPIC, ACTIVITY, new Date());

        assertEquals(before, GameSloEvents.rewardIdempotentConflicts(),
                "正常重放不是失败，不得计入幂等冲突");
        assertFalse(award.isFirstCompletion());
        assertTrue(award.getBadges().isEmpty());
    }

    @Test
    void xpWriteFailureAfterAnAbsentFactIsCountedOnce()
    {
        when(badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenAnswer(invocation ->
        {
            PlayerBadge inserted = invocation.getArgument(0);
            inserted.setId(502L);
            return 1;
        });
        when(badgeMapper.selectOne(anyLong(), any(String.class), anyLong())).thenReturn(null);
        IPlayerProgressionService progression = mock(IPlayerProgressionService.class);
        when(progression.onTopicComplete(MEMBER, TOPIC)).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(service, "playerProgressionService", progression);
        IXpBudgetService budget = mock(IXpBudgetService.class);
        when(budget.computeBudget(TOPIC)).thenReturn(Collections.<String, Object>singletonMap("remain", 20));
        ReflectionTestUtils.setField(service, "xpBudgetService", budget);
        IGrowthService growth = mock(IGrowthService.class);
        when(growth.addCompletionExp(MEMBER, 20, TOPIC)).thenReturn(99L);
        ReflectionTestUtils.setField(service, "growthService", growth);
        TopicCompletionXpAwardMapper xpAwards = (TopicCompletionXpAwardMapper)
                ReflectionTestUtils.getField(service, "xpAwardMapper");
        when(xpAwards.selectOne(MEMBER, TOPIC)).thenReturn(null);
        when(xpAwards.insert(eq(MEMBER), eq(TOPIC), eq(20), any(Date.class))).thenReturn(0);

        long before = GameSloEvents.rewardIdempotentConflicts();
        assertThrows(IllegalStateException.class,
                () -> service.grantOnce(MEMBER, TOPIC, ACTIVITY, new Date()));
        assertEquals(before + 1, GameSloEvents.rewardIdempotentConflicts(),
                "XP 发放事实写入失败是真实失败，必须恰好计一次");
    }

    @Test
    void concurrentXpFactInsertIsCountedOnceAndRethrownUnchanged()
    {
        when(badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenAnswer(invocation ->
        {
            PlayerBadge inserted = invocation.getArgument(0);
            inserted.setId(503L);
            return 1;
        });
        when(badgeMapper.selectOne(anyLong(), any(String.class), anyLong())).thenReturn(null);
        IPlayerProgressionService progression = mock(IPlayerProgressionService.class);
        when(progression.onTopicComplete(MEMBER, TOPIC)).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(service, "playerProgressionService", progression);
        IXpBudgetService budget = mock(IXpBudgetService.class);
        when(budget.computeBudget(TOPIC)).thenReturn(Collections.<String, Object>singletonMap("remain", 20));
        ReflectionTestUtils.setField(service, "xpBudgetService", budget);
        IGrowthService growth = mock(IGrowthService.class);
        when(growth.addCompletionExp(MEMBER, 20, TOPIC)).thenReturn(100L);
        ReflectionTestUtils.setField(service, "growthService", growth);
        TopicCompletionXpAwardMapper xpAwards = (TopicCompletionXpAwardMapper)
                ReflectionTestUtils.getField(service, "xpAwardMapper");
        when(xpAwards.selectOne(MEMBER, TOPIC)).thenReturn(null);
        when(xpAwards.insert(eq(MEMBER), eq(TOPIC), eq(20), any(Date.class)))
                .thenThrow(new DuplicateKeyException("uk_topic_completion_xp"));

        long before = GameSloEvents.rewardIdempotentConflicts();
        // 并发写入同一发放事实：原异常原样外抛（不重试、不改契约），恰好计一次。
        assertThrows(DuplicateKeyException.class,
                () -> service.grantOnce(MEMBER, TOPIC, ACTIVITY, new Date()));
        assertEquals(before + 1, GameSloEvents.rewardIdempotentConflicts(),
                "并发 XP 发放事实冲突必须恰好计一次");
    }

}
