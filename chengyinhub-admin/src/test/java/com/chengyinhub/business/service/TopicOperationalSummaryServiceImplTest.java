package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicOperationalListSummaryDTO;
import com.chengyinhub.business.domain.dto.TopicWorkspaceDTO;
import com.chengyinhub.business.mapper.TopicOperationalSummaryMapper;
import com.chengyinhub.business.service.impl.TopicOperationalSummaryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 主题列表的运营摘要必须复用工作台的状态口径，而不是把公开池或报名数伪装成承接/供给事实。
 */
class TopicOperationalSummaryServiceImplTest {

    private static final Long TOPIC_ID = 71L;

    private TopicOperationalSummaryMapper summaryMapper;
    private ITopicOperationalSummaryService service;

    @BeforeEach
    void setUp() {
        summaryMapper = mock(TopicOperationalSummaryMapper.class);
        service = new TopicOperationalSummaryServiceImpl(summaryMapper);
    }

    @Test
    void summary_keepsClubIntentSupplyAndRunReadinessAsSeparateAxes() {
        TopicOperationalListSummaryDTO source = readyStructure();
        source.setPendingClubApplicationCount(2);
        source.setPendingClubInviteCount(1);
        source.setSignedClubCooperationCount(1);
        source.setRequiredChapterCount(2);
        source.setSuppliedRequiredChapterCount(1);
        source.setMissingRequiredChapterCount(1);
        source.setCandidateCount(4);
        source.setAwardedCandidateCount(2);
        source.setMerchantCooperationCount(2);
        source.setActiveOfferCount(1);
        source.setPaidRegistrationCount(6);
        source.setVerifiedHeadCount(2);
        source.setTotalSales(new BigDecimal("180.00"));
        source.setVerifiedSales(new BigDecimal("60.00"));

        when(summaryMapper.selectByTopicIds(anyList())).thenReturn(Collections.singletonList(source));

        Map<Long, TopicOperationalListSummaryDTO> summaries = service.summarize(
                Collections.singletonList(topic(3, 0, 0)));

        TopicOperationalListSummaryDTO summary = summaries.get(TOPIC_ID);
        assertEquals(TopicOperationalListSummaryDTO.ClubFulfillmentState.SIGNED,
                summary.getClubFulfillmentState());
        assertEquals(TopicOperationalListSummaryDTO.MerchantSupplyState.PARTIALLY_READY,
                summary.getMerchantSupplyState());
        assertEquals(TopicWorkspaceDTO.RunReadinessState.BLOCKED, summary.getRunReadiness());
        assertTrue(summary.getRunBlockers().contains(
                TopicWorkspaceDTO.RunBlocker.REQUIRED_CHAPTER_SUPPLY_MISSING));
        assertTrue(summary.getRunBlockers().contains(TopicWorkspaceDTO.RunBlocker.CLUB_INVITE_PENDING));
        assertFalse(summary.getRunBlockers().contains(
                TopicWorkspaceDTO.RunBlocker.CLUB_FULFILLMENT_UNCONFIRMED),
                "已签俱乐部合作不能被待处理邀约抹成“未承接”");
        assertEquals(4, summary.getCandidateCount());
        assertEquals(2, summary.getAwardedCandidateCount());
        assertEquals(2, summary.getMerchantCooperationCount());
        assertEquals(1, summary.getActiveOfferCount());
        assertEquals(6, summary.getPaidRegistrationCount());
        assertEquals(2, summary.getVerifiedHeadCount());
    }

    @Test
    void partiallyFilledRequiredChapterIsPartiallyReadyButStillBlocked() {
        TopicOperationalListSummaryDTO source = readyStructure();
        source.setSuppliedRequiredChapterCount(0);
        source.setMissingRequiredChapterCount(1);
        source.setActiveOfferCount(1);
        when(summaryMapper.selectByTopicIds(anyList())).thenReturn(Collections.singletonList(source));

        TopicOperationalListSummaryDTO summary = service.summarize(
                Collections.singletonList(topic(3, 0, 0))).get(TOPIC_ID);

        assertEquals(TopicOperationalListSummaryDTO.MerchantSupplyState.PARTIALLY_READY,
                summary.getMerchantSupplyState(),
                "一个已生效商家供给说明正在补齐，但章节目标尚未满足");
        assertTrue(summary.getRunBlockers().contains(
                TopicWorkspaceDTO.RunBlocker.REQUIRED_CHAPTER_SUPPLY_MISSING),
                "部分到位不能把需两家承接的章节伪装为可开跑");
    }

    @Test
    void onlyLifecycleBlocker_isRiskInsteadOfPretendingReady() {
        TopicOperationalListSummaryDTO source = readyStructure();
        when(summaryMapper.selectByTopicIds(anyList())).thenReturn(Collections.singletonList(source));

        TopicOperationalListSummaryDTO summary = service.summarize(
                Collections.singletonList(topic(2, 0, 1))).get(TOPIC_ID);

        assertEquals(TopicWorkspaceDTO.RunReadinessState.AT_RISK, summary.getRunReadiness());
        assertEquals(Collections.singletonList(TopicWorkspaceDTO.RunBlocker.LIFECYCLE_NOT_READY),
                summary.getRunBlockers());
    }

    @Test
    void settledAndClosedTopics_areBlockedInsteadOfPretendingRunnable() {
        TopicOperationalListSummaryDTO source = readyStructure();
        when(summaryMapper.selectByTopicIds(anyList())).thenReturn(Collections.singletonList(source));

        for (Integer terminalLifecycle : Arrays.asList(5, 6)) {
            TopicOperationalListSummaryDTO summary = service.summarize(
                    Collections.singletonList(topic(terminalLifecycle, 0, 1))).get(TOPIC_ID);

            assertEquals(TopicWorkspaceDTO.RunReadinessState.BLOCKED, summary.getRunReadiness());
            assertEquals(Collections.singletonList(TopicWorkspaceDTO.RunBlocker.LIFECYCLE_TERMINAL),
                    summary.getRunBlockers());
        }
    }

    @Test
    void rejectedCandidateHistory_doesNotPretendSupplyCanStillBeAllocated() {
        TopicOperationalListSummaryDTO source = readyStructure();
        source.setSuppliedRequiredChapterCount(0);
        source.setMissingRequiredChapterCount(1);
        source.setCandidateCount(1);
        source.setPendingCandidateCount(0);
        source.setApprovedCandidateCount(0);
        when(summaryMapper.selectByTopicIds(anyList())).thenReturn(Collections.singletonList(source));

        TopicOperationalListSummaryDTO summary = service.summarize(
                Collections.singletonList(topic(3, 1, 0))).get(TOPIC_ID);

        assertEquals(TopicOperationalListSummaryDTO.MerchantSupplyState.GAP,
                summary.getMerchantSupplyState(),
                "已被拒绝的历史报名不能把缺供给主题伪装为待调配");
    }

    @Test
    void absentBatchProjection_isNotInventedAsAllZeroSummary() {
        when(summaryMapper.selectByTopicIds(anyList())).thenReturn(Collections.emptyList());

        Map<Long, TopicOperationalListSummaryDTO> summaries = service.summarize(
                Collections.singletonList(topic(3, 0, 0)));

        assertFalse(summaries.containsKey(TOPIC_ID),
                "批量投影未读回时，控制器必须让前端显示不可用，而不是把它当成零值绿灯");
    }

    private static CmsTopic topic(int lifecycle, int selfPlay, int merchantStatus) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setLifecycle(lifecycle);
        topic.setSelfPlay(selfPlay);
        topic.setMerchantStatus(merchantStatus);
        return topic;
    }

    private static TopicOperationalListSummaryDTO readyStructure() {
        TopicOperationalListSummaryDTO source = new TopicOperationalListSummaryDTO();
        source.setTopicId(TOPIC_ID);
        source.setChapterCount(1);
        source.setChapterWithoutNodeCount(0);
        source.setTicketCount(1);
        source.setRequiredChapterCount(1);
        source.setSuppliedRequiredChapterCount(1);
        source.setMissingRequiredChapterCount(0);
        return source;
    }
}
