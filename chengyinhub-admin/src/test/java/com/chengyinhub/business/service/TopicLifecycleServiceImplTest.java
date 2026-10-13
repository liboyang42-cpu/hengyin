package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.TopicLifecycleServiceImpl;
import com.chengyinhub.business.service.support.CommercialReviewStatusSource;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicLifecycleServiceImplTest {

    private CmsTopicMapper topicMapper;
    private CommercialTopicPolicy commercialTopicPolicy;
    private CommercialReviewStatusSource reviewStatusSource;
    private TopicLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        commercialTopicPolicy = mock(CommercialTopicPolicy.class);
        reviewStatusSource = mock(CommercialReviewStatusSource.class);
        service = new TopicLifecycleServiceImpl(topicMapper, commercialTopicPolicy, reviewStatusSource);
        when(commercialTopicPolicy.evaluate(anyLong())).thenReturn(CommercialTopicPolicy.Evaluation.ugc());
    }

    @Test
    void commercialDraftCannotBypassProjectReviewThroughEitherExit() {
        when(commercialTopicPolicy.evaluate(7L)).thenReturn(CommercialTopicPolicy.Evaluation.commercial("paidTicket", 11L));

        assertThrows(IllegalStateException.class,
                () -> service.transition(7L, TopicLifecycleState.DRAFT, TopicLifecycleState.RECRUITING));
        assertThrows(IllegalStateException.class,
                () -> service.transition(7L, TopicLifecycleState.DRAFT, TopicLifecycleState.PRICING));
        verify(topicMapper, never()).transitionLifecycle(anyLong(), anyInt(), anyInt(), any());
    }

    @Test
    void approvedProjectReviewCanLeavePendingOnlyForItsRequestedTarget() {
        when(topicMapper.transitionLifecycle(anyLong(), anyInt(), anyInt(), any())).thenReturn(1);
        when(topicMapper.selectCmsTopicById(7L)).thenReturn(published());

        assertTrue(service.transition(7L, TopicLifecycleState.PENDING_REVIEW, TopicLifecycleState.RECRUITING));

        verify(reviewStatusSource).assertProjectApproved(7L, TopicLifecycleState.RECRUITING);
        verify(topicMapper).transitionLifecycle(eq(7L), eq(TopicLifecycleState.PENDING_REVIEW.getCode()), eq(1), any());
    }

    /**
     * D5:立项审(TOPIC_PROJECT_REVIEW)与内容先审后发(BIZ_TOPIC)是两条各自独立的审核任务,
     * 两条都过才算真的开放招商。只审了立项那条时,lifecycle 会显示 RECRUITING,而商家侧
     * {@code MerchantChapterApplicationServiceImpl.assertRecruitmentOpen} 恒红
     * 「主题未上架或未过审」—— 平台侧却没有任何地方显示「还差一个审」。
     * 这道断言把那个组合态从「看着通过、闸恒红」变成「审核台当场红并点名差哪个」。
     */
    @Test
    void pendingReviewCannotLeaveWhileContentReviewStillPending() {
        when(topicMapper.transitionLifecycle(anyLong(), anyInt(), anyInt(), any())).thenReturn(1);
        when(topicMapper.selectCmsTopicById(7L)).thenReturn(topic(0, 0));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.transition(7L, TopicLifecycleState.PENDING_REVIEW, TopicLifecycleState.RECRUITING));
        // 断言点名的是【哪一个审】没过,不是「红了就行」——只说「不允许」等于让人自己去猜。
        assertTrue(e.getMessage().contains("内容审核"), e.getMessage());
        verify(topicMapper, never()).transitionLifecycle(anyLong(), anyInt(), anyInt(), any());
    }

    /**
     * 过审但配额满那一支:{@code applyModeration} 会写 status=1、userStatus=0(不自动上架)。
     * 商家闸两个字段都要 1,所以这半边同样必须挡住 —— 只看 status 会漏掉它。
     */
    @Test
    void pendingReviewCannotLeaveWhenApprovedButNotOnlineDueToQuota() {
        when(topicMapper.transitionLifecycle(anyLong(), anyInt(), anyInt(), any())).thenReturn(1);
        when(topicMapper.selectCmsTopicById(7L)).thenReturn(topic(1, 0));

        assertThrows(IllegalStateException.class,
                () -> service.transition(7L, TopicLifecycleState.PENDING_REVIEW, TopicLifecycleState.PRICING));
        verify(topicMapper, never()).transitionLifecycle(anyLong(), anyInt(), anyInt(), any());
    }

    private static com.chengyinhub.business.domain.CmsTopic published() {
        return topic(1, 1);
    }

    private static com.chengyinhub.business.domain.CmsTopic topic(int status, int userStatus) {
        com.chengyinhub.business.domain.CmsTopic t = new com.chengyinhub.business.domain.CmsTopic();
        t.setId(7L);
        t.setStatus(status);
        t.setUserStatus(userStatus);
        return t;
    }

    @Test
    void unknownPersistedLifecycleCodeFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> TopicLifecycleState.fromCode(99));
    }

    @Test
    void recruitDeadlineCanOnlyReturnDraftOrEnterPricing() {
        when(topicMapper.transitionLifecycle(anyLong(), anyInt(), anyInt(), any())).thenReturn(1);

        assertTrue(service.transition(7L, TopicLifecycleState.RECRUITING, TopicLifecycleState.DRAFT));
        assertTrue(service.transition(8L, TopicLifecycleState.RECRUITING, TopicLifecycleState.PRICING));
        verify(topicMapper).transitionLifecycle(eq(7L), eq(1), eq(0), any());
        verify(topicMapper).transitionLifecycle(eq(8L), eq(1), eq(2), any());
    }

    @Test
    void pricingMustUseAtomicFinalizerAndCannotSkipDirectlyToClosed() {
        when(topicMapper.transitionLifecycle(anyLong(), anyInt(), anyInt(), any())).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> service.transition(7L, TopicLifecycleState.PRICING, TopicLifecycleState.SELLING));
        assertThrows(IllegalArgumentException.class,
                () -> service.transition(7L, TopicLifecycleState.PRICING, TopicLifecycleState.CLOSED));
    }

    @Test
    void commercialPricingCannotBypassAtomicFinalizerEvenWithReviewSeam() {
        when(commercialTopicPolicy.evaluate(7L))
                .thenReturn(CommercialTopicPolicy.Evaluation.commercial("merchantSupply", 31L));
        org.mockito.Mockito.doThrow(new IllegalStateException("开售审核未通过"))
                .when(reviewStatusSource).assertSaleApproved(7L);

        assertThrows(IllegalStateException.class,
                () -> service.transition(7L, TopicLifecycleState.PRICING, TopicLifecycleState.SELLING));
        verify(reviewStatusSource, never()).assertSaleApproved(7L);
        verify(topicMapper, never()).transitionLifecycle(anyLong(), anyInt(), anyInt(), any());
    }

    @Test
    void staleExpectedStateReturnsFalseInsteadOfOverwritingNewerState() {
        when(topicMapper.transitionLifecycle(anyLong(), anyInt(), anyInt(), any())).thenReturn(0);

        assertFalse(service.transition(7L, TopicLifecycleState.RECRUITING, TopicLifecycleState.PRICING));
    }

    @Test
    void sameStateAndNullsAreRejectedBeforeSql() {
        assertThrows(IllegalArgumentException.class,
                () -> service.transition(7L, TopicLifecycleState.DRAFT, TopicLifecycleState.DRAFT));
        assertThrows(IllegalArgumentException.class,
                () -> service.transition(null, TopicLifecycleState.DRAFT, TopicLifecycleState.RECRUITING));
        verify(topicMapper, never()).transitionLifecycle(anyLong(), anyInt(), anyInt(), any());
    }
}
