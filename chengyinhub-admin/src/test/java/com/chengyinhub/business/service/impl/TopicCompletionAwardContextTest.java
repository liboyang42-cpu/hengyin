package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.constant.RewardPolicy;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The real award bean's backward topic read under Spring's forbidden circular references. */
class TopicCompletionAwardContextTest {
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> ((DefaultListableBeanFactory) ctx.getBeanFactory()).setAllowCircularReferences(false))
                .withUserConfiguration(ForwardTopicFixture.class)
                .withBean(PlayerBadgeMapper.class, () -> mock(PlayerBadgeMapper.class))
                .withBean(IPointsAwardService.class, () -> mock(IPointsAwardService.class))
                .withBean(IPlayerProgressionService.class, () -> mock(IPlayerProgressionService.class))
                .withBean(ITopicCompletionCouponRewardService.class, () -> mock(ITopicCompletionCouponRewardService.class))
                .withBean(IOfficialEventV2Service.class, () -> mock(IOfficialEventV2Service.class))
                .withBean(IXpBudgetService.class, () -> mock(IXpBudgetService.class))
                .withBean(IGrowthService.class, () -> mock(IGrowthService.class))
                .withBean(TopicCompletionXpAwardMapper.class, () -> mock(TopicCompletionXpAwardMapper.class))
                .withBean(GrowthMissionProgressMapper.class, () -> mock(GrowthMissionProgressMapper.class))
                .withBean(IUmsMemberService.class, () -> mock(IUmsMemberService.class));
    }
    @Configuration(proxyBeanMethods = false)
    static class ForwardTopicFixture {
        // Reproduce the actual topic -> route -> award forward dependencies without CmsTopic's unrelated graph.
        @Bean ForwardRoute route(ITopicCompletionAwardService award) { return new ForwardRoute(award); }
        @Bean TopicReadback facts() { return new TopicReadback(); }
        @Bean ICmsTopicService topic(ForwardRoute route, TopicReadback facts) {
            assertNotNull(route.award);
            return (ICmsTopicService) Proxy.newProxyInstance(ICmsTopicService.class.getClassLoader(),
                    new Class<?>[]{ICmsTopicService.class}, (proxy, method, args) -> {
                        if (method.getName().equals("selectCmsTopicById")) {
                            facts.reads.incrementAndGet(); return facts.topic;
                        }
                        if (method.getName().equals("toString")) return "topic-readback-fixture";
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        throw new UnsupportedOperationException("Unexpected topic method: " + method.getName());
                    });
        }
    }
    static class ForwardRoute {
        final ITopicCompletionAwardService award;
        ForwardRoute(ITopicCompletionAwardService award) { this.award = award; }
    }
    static class TopicReadback { final AtomicInteger reads = new AtomicInteger(); CmsTopic topic; }
    static class EagerAward extends TopicCompletionAwardServiceImpl {
        @Autowired private ICmsTopicService eagerBackEdge;
    }
    @Test void realLazyAwardInitializesAndItsFirstReadResolvesTheRegisteredTopicBean() {
        context().withBean(TopicCompletionAwardServiceImpl.class, TopicCompletionAwardServiceImpl::new).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertFalse(((DefaultListableBeanFactory) ctx.getBeanFactory()).isAllowCircularReferences());
            ITopicCompletionAwardService award = ctx.getBean(ITopicCompletionAwardService.class);
            assertSame(award, ctx.getBean(ForwardRoute.class).award);
            TopicReadback facts = ctx.getBean(TopicReadback.class);
            CmsTopic topic = new CmsTopic(); topic.setId(7L); topic.setName("真实回读主题"); facts.topic = topic;
            assertEquals(0, facts.reads.get(), "Lazy wiring may not read business state while constructing beans");
            PlayerBadgeMapper badges = ctx.getBean(PlayerBadgeMapper.class);
            when(badges.insertPlayerBadge(any())).thenReturn(1);
            when(ctx.getBean(IPlayerProgressionService.class).onTopicComplete(11L, 7L)).thenReturn(List.of());
            when(ctx.getBean(IXpBudgetService.class).computeBudget(7L)).thenReturn(Map.of("remain", 0));
            assertTrue(award.grantOnce(11L, 7L, 100L, null, RewardPolicy.XP_BADGE_ONLY).isFirstCompletion());
            assertEquals(1, facts.reads.get());
            ArgumentCaptor<PlayerBadge> inserted = ArgumentCaptor.forClass(PlayerBadge.class);
            verify(badges).insertPlayerBadge(inserted.capture());
            assertEquals("真实回读主题 · 通关", inserted.getValue().getBadgeName());
            facts.topic = null;
            IllegalStateException missing = assertThrows(IllegalStateException.class,
                    () -> award.grantOnce(11L, 8L, 100L, null, RewardPolicy.XP_BADGE_ONLY));
            assertTrue(missing.getMessage().contains("主题不存在")); assertEquals(2, facts.reads.get());
            verify(badges, times(1)).insertPlayerBadge(any());
        });
    }
    @Test void eagerReverseEdgeStillFailsInsteadOfAllowingCircularReferences() {
        context().withBean(EagerAward.class, EagerAward::new).run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            Throwable failure = ctx.getStartupFailure(); boolean cycle = false;
            while (failure != null) {
                if (failure instanceof org.springframework.beans.factory.BeanCurrentlyInCreationException) cycle = true;
                failure = failure.getCause();
            }
            assertTrue(cycle, "Mutation with eager reverse dependency must reproduce the actual Spring cycle");
        });
    }
}
