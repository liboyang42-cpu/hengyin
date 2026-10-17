package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminator;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T1 升级：截止决策只读 AcceptedLineup，invite/报名回执不得冒充已生效合同。 */
class TopicRecruitDeadlineServiceTest {

    private static final Long TOPIC_ID = 73L;
    private static final Long POISON_TOPIC_ID = 74L;

    private CmsTopicMapper topicMapper;
    private MerchantChapterOfferMapper offerMapper;
    private ITopicLifecycleService lifecycleService;
    private ITopicSaleFinalizationService finalizationService;
    private ITopicCommercialReviewService commercialReviewService;
    private IPricingService pricingService;
    private CoopOrderLineupSource lineupSource;
    private CmsTopicServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        lifecycleService = mock(ITopicLifecycleService.class);
        finalizationService = mock(ITopicSaleFinalizationService.class);
        commercialReviewService = mock(ITopicCommercialReviewService.class);
        pricingService = mock(IPricingService.class);
        lineupSource = mock(CoopOrderLineupSource.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(lifecycleService.transition(anyLong(), eq(TopicLifecycleState.RECRUITING), any(TopicLifecycleState.class)))
                .thenReturn(true);
        when(offerMapper.selectByTopic(TOPIC_ID)).thenReturn(Collections.emptyList());
        ObjectProvider<PendingRecruitWorkflowTerminator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(PendingRecruitWorkflowTerminator.class));

        service = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "merchantChapterOfferMapper", offerMapper);
        ReflectionTestUtils.setField(service, "topicLifecycleService", lifecycleService);
        ReflectionTestUtils.setField(service, "topicSaleFinalizationService", finalizationService);
        ReflectionTestUtils.setField(service, "topicCommercialReviewService", commercialReviewService);
        ReflectionTestUtils.setField(service, "pricingService", pricingService);
        ReflectionTestUtils.setField(service, "coopOrderLineupSource", lineupSource);
        ReflectionTestUtils.setField(service, "pendingRecruitWorkflowTerminatorProvider", provider);
        ReflectionTestUtils.setField(service, "transactionManager", txManager);
    }

    @Test
    void acceptedInviteWithoutEffectiveOrderReturnsTopicToDraft() {
        givenExpiredRecruitingTopic(ProductType.ORIENTEERING);
        // 故意没有 order：即使库里存在 CoopInvite.status=1，运行时阵容仍为空。
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(emptyLineup());

        int handled = service.processRecruitDeadlines();

        assertEquals(1, handled, "到期主题必须被处理，不能继续卡在 RECRUITING");
        assertTransitionTarget(TopicLifecycleState.DRAFT);
        verify(finalizationService, never()).finalizeForSale(anyLong());
    }

    @Test
    void normalizedCashObligationEntersPricingWithoutAutoSale() {
        givenExpiredRecruitingTopic(ProductType.ORIENTEERING);
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(new CoopOrderLineupSource.ReadResult(
                Collections.singletonList(new CoopOrderLineupSource.LineupEntry("CLUB", 9L, "ORGANIZER", "TOPIC",
                        TOPIC_ID, "COOP_ORDER", 19L, true)), Collections.emptyList()));

        int handled = service.processRecruitDeadlines();

        assertEquals(1, handled);
        assertTransitionTarget(TopicLifecycleState.PRICING);
        verify(finalizationService, never()).finalizeForSale(anyLong());
    }

    @Test
    void normalizedZeroCashLineupStopsAtPricingForExplicitCanonicalSubmission() {
        givenExpiredRecruitingTopic(ProductType.FREE_EXPLORE);
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(new CoopOrderLineupSource.ReadResult(
                Collections.singletonList(new CoopOrderLineupSource.LineupEntry("MERCHANT", 8L, "SUPPLY", "CHAPTER",
                        18L, "MERCHANT_CHAPTER_OFFER", 28L, false)), Collections.emptyList()));

        int handled = service.processRecruitDeadlines();

        assertEquals(1, handled);
        assertTransitionTarget(TopicLifecycleState.PRICING);
        verify(pricingService).freezeFeeSnapshot(TOPIC_ID);
        verify(commercialReviewService, never()).submitSaleReview(anyLong(), any());
        verify(finalizationService, never()).finalizeForSale(TOPIC_ID);
    }

    /**
     * ★ 一个必抛的主题不得冻住它后面的主题(2026-08-14 修的存量缺陷)。
     *
     * <p>原来这个 for 循环没有 try/catch,而 finalizeForSale 在**合法业务状态**下就会抛
     * IllegalStateException(必选章无 offer / 售卖时间非法 / 阵容重复)。异常冲出循环 ⇒
     * 事务回滚 ⇒ lifecycle 仍是 1、recruit_deadline 仍过期 ⇒ 下轮再选中再抛,
     * 而**它后面所有主题永远处理不到**,连 advanceSaleLifecycle() 都到不了,零告警。
     *
     * <p>断言的核心不是「第一个主题失败了」,而是**第二个主题仍然被处理**。
     */
    @Test
    void multipleZeroCashTopicsAllStopAtPricingWithoutImplicitSubmission() {
        CmsTopic poisoned = expiredTopic(POISON_TOPIC_ID, ProductType.FREE_EXPLORE);
        CmsTopic healthy = expiredTopic(TOPIC_ID, ProductType.FREE_EXPLORE);
        when(topicMapper.selectRecruitExpiredTopics(any(Date.class)))
                .thenReturn(java.util.Arrays.asList(poisoned, healthy));
        when(topicMapper.selectCmsTopicByIdForUpdate(POISON_TOPIC_ID)).thenReturn(poisoned);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(healthy);
        when(lifecycleService.transition(anyLong(), eq(TopicLifecycleState.RECRUITING), any(TopicLifecycleState.class)))
                .thenReturn(true);
        when(offerMapper.selectByTopic(POISON_TOPIC_ID)).thenReturn(Collections.emptyList());
        CoopOrderLineupSource.ReadResult zeroCash = new CoopOrderLineupSource.ReadResult(
                Collections.singletonList(new CoopOrderLineupSource.LineupEntry("MERCHANT", 8L, "SUPPLY", "CHAPTER",
                        18L, "MERCHANT_CHAPTER_OFFER", 28L, false)), Collections.emptyList());
        when(lineupSource.readEffectiveLineup(POISON_TOPIC_ID)).thenReturn(zeroCash);
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(zeroCash);
        int handled = service.processRecruitDeadlines();

        verify(commercialReviewService, never()).submitSaleReview(anyLong(), any());
        assertEquals(2, handled, "两个主题都应推进到 PRICING 并等待显式提审");
    }

    /**
     * ★ D8 真根因:{@link PendingRecruitWorkflowTerminator} 这个 seam 在 main 代码里
     * <b>一个实现都没有</b>(全仓只有接口 + 两处 ObjectProvider 注入),生产上
     * {@code getIfAvailable()} 恒为 null。原来的 null 分支<b>无条件</b>把主题打回 DRAFT ——
     * 招到 4 家商家的探店日,招募一到期照样被判「招募未成,已回草稿」,handled 还 +1,
     * 全程零错误日志。孪生调用点 {@code TopicSaleFinalizationServiceImpl:140-142} 对同一个
     * 缺失 seam 是<b>抛异常拒绝开售</b> —— 同一个洞,一处响一处哑,哑的那处还顺手毁了状态。
     *
     * <p>本用例是本套件里唯一<b>不</b>把 terminator 塞成 mock 的:其余用例全都替生产补上了一个
     * 生产里不存在的 bean,于是这条路径从来没被任何测试走过(假绿的第 6 种:驱动层绕过被测机制)。
     */
    @Test
    void missingTerminatorSeamMustNotSilentlyReturnRecruitedTopicToDraft() {
        ObjectProvider<PendingRecruitWorkflowTerminator> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);   // ← 生产真实情况:零实现
        ReflectionTestUtils.setField(service, "pendingRecruitWorkflowTerminatorProvider", empty);
        givenExpiredRecruitingTopic(ProductType.FREE_EXPLORE);
        when(offerMapper.selectByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(validOffer()));
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(emptyLineup());

        int handled = service.processRecruitDeadlines();

        // 断言的是「状态没有被毁掉」,不是「返回了几」:主题必须原地留在 RECRUITING 等人接 seam。
        verify(lifecycleService, never()).transition(anyLong(), any(TopicLifecycleState.class),
                any(TopicLifecycleState.class));
        assertEquals(0, handled, "seam 缺失时不算处理成功,否则计数会假装一切正常");
    }

    /**
     * ★ seam 接上之后,招募到期必须真的把 lifecycle 从 1(RECRUITING)推到 2(PRICING)。
     *
     * <p>本用例<b>不</b>把 terminator 塞成 mock,而是装真正的
     * {@link com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminatorImpl} ——
     * 断言的是「有实现之后这一跳真的走通了」,不是「容器里有个 bean」。
     * 它在实现类存在之前连编译都过不去,那就是本条的红。
     * (两条 SQL 自己的「只动本期」「可重放」由 PendingRecruitTerminationMyBatisIT 在真库上承重。)
     */
    @Test
    void withRealTerminatorSeamRecruitDeadlineAdvancesRecruitingToPricing() {
        com.chengyinhub.business.mapper.CoopInviteMapper inviteMapper =
                mock(com.chengyinhub.business.mapper.CoopInviteMapper.class);
        com.chengyinhub.business.mapper.MerchantChapterApplicationMapper applicationMapper =
                mock(com.chengyinhub.business.mapper.MerchantChapterApplicationMapper.class);
        when(inviteMapper.countPendingByTopic(TOPIC_ID)).thenReturn(1);
        when(inviteMapper.expirePendingInvitesByTopic(eq(TOPIC_ID), any(Date.class))).thenReturn(1);
        when(applicationMapper.countPendingByTopic(TOPIC_ID)).thenReturn(2);
        when(applicationMapper.rejectPendingByTopic(eq(TOPIC_ID), any(), any(Date.class))).thenReturn(2);
        ObjectProvider<PendingRecruitWorkflowTerminator> real = mock(ObjectProvider.class);
        when(real.getIfAvailable()).thenReturn(
                new com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminatorImpl(
                        inviteMapper, applicationMapper));
        ReflectionTestUtils.setField(service, "pendingRecruitWorkflowTerminatorProvider", real);

        givenExpiredRecruitingTopic(ProductType.FREE_EXPLORE);
        when(offerMapper.selectByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(validOffer()));
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(emptyLineup());

        int handled = service.processRecruitDeadlines();

        assertEquals(1, handled);
        assertTransitionTarget(TopicLifecycleState.PRICING);
        // 悬着的东西真的被收干净了,不是只走了个过场
        verify(inviteMapper).expirePendingInvitesByTopic(eq(TOPIC_ID), any(Date.class));
        verify(applicationMapper).rejectPendingByTopic(eq(TOPIC_ID), any(), any(Date.class));
    }

    /** 先数与实写行数对不上 = 调用方的 topic 行锁假设被破坏,必须整体失败,不许写一半就走。 */
    @Test
    void terminatorFailsClosedWhenCountedRowsDoNotMatchWrittenRows() {
        com.chengyinhub.business.mapper.CoopInviteMapper inviteMapper =
                mock(com.chengyinhub.business.mapper.CoopInviteMapper.class);
        com.chengyinhub.business.mapper.MerchantChapterApplicationMapper applicationMapper =
                mock(com.chengyinhub.business.mapper.MerchantChapterApplicationMapper.class);
        when(inviteMapper.countPendingByTopic(TOPIC_ID)).thenReturn(3);
        when(inviteMapper.expirePendingInvitesByTopic(eq(TOPIC_ID), any(Date.class))).thenReturn(2);
        com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminatorImpl terminator =
                new com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminatorImpl(
                        inviteMapper, applicationMapper);

        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> terminator.terminatePendingUnderTopicLock(TOPIC_ID, new Date()));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("coop_invite"), e.getMessage());
        // 第一步没对上就不许走第二步
        verify(applicationMapper, never()).rejectPendingByTopic(anyLong(), any(), any(Date.class));
    }

    private com.chengyinhub.business.domain.MerchantChapterOffer validOffer() {
        com.chengyinhub.business.domain.MerchantChapterOffer offer =
                new com.chengyinhub.business.domain.MerchantChapterOffer();
        offer.setTopicId(TOPIC_ID);
        offer.setStatus(1);
        offer.setValidEnd(new Date(System.currentTimeMillis() + 86_400_000L));
        return offer;
    }

    private CmsTopic expiredTopic(Long id, int productType) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        topic.setProductType(productType);
        topic.setLifecycle(TopicLifecycleState.RECRUITING.getCode());
        topic.setRecruitDeadline(new Date(System.currentTimeMillis() - 1_000L));
        return topic;
    }

    private CoopOrderLineupSource.ReadResult emptyLineup() {
        return new CoopOrderLineupSource.ReadResult(Collections.emptyList(), Collections.emptyList());
    }

    private void givenExpiredRecruitingTopic(int productType) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setProductType(productType);
        topic.setLifecycle(TopicLifecycleState.RECRUITING.getCode());
        topic.setRecruitDeadline(new Date(System.currentTimeMillis() - 1_000L));
        when(topicMapper.selectRecruitExpiredTopics(any(Date.class))).thenReturn(Collections.singletonList(topic));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
    }

    private void assertTransitionTarget(TopicLifecycleState expected) {
        ArgumentCaptor<TopicLifecycleState> target = ArgumentCaptor.forClass(TopicLifecycleState.class);
        verify(lifecycleService).transition(eq(TOPIC_ID), eq(TopicLifecycleState.RECRUITING), target.capture());
        assertEquals(expected, target.getValue(), "决策必须来自规范化阵容而不是工作流回执");
    }
}
