package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-P1(2026-10-20 分级审核):开售审核与内容编辑解耦。
 *
 * <p>全量重建档(待审/驳回)里的 PRICING 主题:
 * <ul>
 *   <li>只改故事/名称/简介 → 不递增 canonical 版本(开售审核不重置),待审开售单原地刷新快照;</li>
 *   <li>改价格 → 照旧递增版本(= 重置开售审核),不刷新旧单。</li>
 * </ul>
 */
class TopicSaleReviewDecouplingTest {

    private static final Long ME = 100L;
    private static final Long TOPIC = 7L;

    private CmsTopicMapper topicMapper;
    private IAuditTaskService auditTaskService;
    private ITopicSaleCanonicalMutationService canonicalMutationService;
    private ITopicCommercialReviewService commercialReviewService;
    private CmsTopicServiceImpl svc;
    private CmsTopic stored;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        doAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicChapter chapter = call.getArgument(0);
            chapter.setId(801L);
            return 1;
        }).when(chapterMapper).insertCmsTopicChapter(any());
        doAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicNode node = call.getArgument(0);
            node.setId(901L);
            return 1;
        }).when(nodeMapper).insertCmsTopicNode(any());
        doAnswer(call -> {
            CmsActivity activity = call.getArgument(0);
            activity.setId(1001L);
            return 1;
        }).when(activityMapper).insertCmsActivity(any());
        auditTaskService = mock(IAuditTaskService.class);
        canonicalMutationService = mock(ITopicSaleCanonicalMutationService.class);
        commercialReviewService = mock(ITopicCommercialReviewService.class);

        svc = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(svc, "supplyReviewService", mock(
                com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? topicMapper.selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(svc, "quotaService", mock(IQuotaService.class));
        when(topicMapper.publishRouteSnapshot(any(), any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(svc, "settlementDebtLedger",
                mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        ReflectionTestUtils.setField(svc, "commercialTopicPolicy",
                new com.chengyinhub.business.service.support.CommercialTopicPolicy(ticketMapper, chapterMapper,
                        mock(com.chengyinhub.business.mapper.MerchantChapterOfferMapper.class), topicMapper));
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(svc, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(svc, "cmsCollaboratorsMapper", mock(CmsCollaboratorsMapper.class));
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(svc, "couponRewardAuthorizationService", mock(ICouponRewardAuthorizationService.class));
        ReflectionTestUtils.setField(svc, "clubService", mock(IClubService.class));
        ReflectionTestUtils.setField(svc, "xpBudgetService", mock(IXpBudgetService.class));
        ReflectionTestUtils.setField(svc, "auditTaskService", auditTaskService);
        ReflectionTestUtils.setField(svc, "omsTicketService", mock(IOmsTicketService.class));
        ReflectionTestUtils.setField(svc, "canonicalMutationService", canonicalMutationService);
        ReflectionTestUtils.setField(svc, "topicCommercialReviewService", commercialReviewService);
        when(registrationMapper.selectCmsRegistrationList(any())).thenReturn(Collections.<CmsRegistration>emptyList());
        when(activityMapper.selectCmsActivityList(any())).thenReturn(Collections.<CmsActivity>emptyList());
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);

        // 驳回档(status=2)⇒ 全量重建;生命周期 PRICING,自玩价 5 元
        stored = new CmsTopic();
        stored.setId(TOPIC);
        stored.setMemberId(ME);
        stored.setStatus(2);
        stored.setLifecycle(TopicLifecycleState.PRICING.getCode());
        stored.setDelFlag(0);
        stored.setStartDate(daysFromNow(3));
        stored.setEndDate(daysFromNow(5));
        stored.setName("旧名字");
        stored.setSelfPlay(1);
        stored.setSelfPlayPrice(new BigDecimal("5"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(stored);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(0);
        doAnswer(invocation -> {
            CmsTopic patch = invocation.getArgument(0);
            if (patch.getName() != null) stored.setName(patch.getName());
            if (patch.getSelfPlayPrice() != null) stored.setSelfPlayPrice(patch.getSelfPlayPrice());
            if (patch.getStatus() != null) stored.setStatus(patch.getStatus());
            if (patch.getUserStatus() != null) stored.setUserStatus(patch.getUserStatus());
            return 1;
        }).when(topicMapper).updateCmsTopic(any());

        AuditTask pendingSale = new AuditTask();
        pendingSale.setId(55L);
        pendingSale.setBizType(AuditTask.BIZ_TOPIC_SALE_REVIEW);
        pendingSale.setBizId(TOPIC);
        pendingSale.setStatus(AuditTask.STATUS_PENDING);
        when(auditTaskService.selectAuditTaskList(any())).thenAnswer(call -> {
            AuditTask q = call.getArgument(0);
            return AuditTask.BIZ_TOPIC_SALE_REVIEW.equals(q.getBizType())
                    ? new ArrayList<>(Collections.singletonList(pendingSale)) : new ArrayList<AuditTask>();
        });
    }

    private static Date daysFromNow(int days) {
        return new Date(System.currentTimeMillis() + days * 24L * 3600_000L);
    }

    private TopicUpdateDTO dto(String name, String story, String selfPlayPrice) {
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC);
        dto.setName(name);
        dto.setDescription("简介");
        dto.setJourneyStory(story);
        dto.setProductType(ProductType.ORIENTEERING);
        dto.setStartDate(stored.getStartDate());
        dto.setEndDate(stored.getEndDate());
        dto.setSelfPlay(1);
        dto.setSelfPlayPrice(new BigDecimal(selfPlayPrice));
        NodeDTO node = new NodeDTO();
        node.setName("第一站");
        node.setAddress("人民广场");
        node.setNodeTime(30);
        ChapterDTO chapter = new ChapterDTO();
        chapter.setName("第一章");
        chapter.setNodes(new ArrayList<>(Arrays.asList(node)));
        dto.setChapters(new ArrayList<>(Arrays.asList(chapter)));
        dto.setTickets(new ArrayList<ActivityTicketRequest>());
        return dto;
    }

    @Test
    void storyAndNameEdit_keepsSaleReview_andRefreshesThePendingTask() {
        svc.updateTopicByOwner(dto("新名字", null, "5"), ME, "me");

        verify(canonicalMutationService, never()).invalidateApprovedCanonical(anyLong(), anyString());
        verify(commercialReviewService, times(1)).submitSaleReview(TOPIC, ME);
    }

    @Test
    void priceEdit_resetsSaleReview() {
        svc.updateTopicByOwner(dto("旧名字", null, "8"), ME, "me");

        verify(canonicalMutationService, times(1)).invalidateApprovedCanonical(eq(TOPIC), eq("topic-content-rebuild"));
        verify(commercialReviewService, never()).submitSaleReview(anyLong(), anyLong());
    }
}
