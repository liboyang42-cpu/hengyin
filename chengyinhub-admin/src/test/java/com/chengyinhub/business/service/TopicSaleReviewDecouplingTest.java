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

    private javax.sql.DataSource publicationDataSource;
    private boolean callerTransactionActive;

    @org.junit.jupiter.api.AfterEach
    void publicationTransactionReleasesItsResource() {
        if (publicationDataSource != null)
            org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(publicationDataSource));
        org.junit.jupiter.api.Assertions.assertEquals(callerTransactionActive,
                org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
    }


    private static final Long ME = 100L;
    private static final Long TOPIC = 7L;

    private CmsTopicMapper topicMapper;
    private IAuditTaskService auditTaskService;
    private ITopicSaleCanonicalMutationService canonicalMutationService;
    private ITopicCommercialReviewService commercialReviewService;
    private CmsTopicServiceImpl svc;
    private CmsTopic stored;
    private com.chengyinhub.business.domain.CmsTopicChapter originalChapter;

    @BeforeEach
    void setUp() {
        callerTransactionActive = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        topicMapper = mock(CmsTopicMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        java.util.List<com.chengyinhub.business.domain.CmsTopicChapter> writtenChapters = new ArrayList<>();
        java.util.List<com.chengyinhub.business.domain.CmsTopicNode> writtenNodes = new ArrayList<>();
        // This case edits existing content, not an empty topic into a new commercial chapter.
        // Freeze all four original sale-term columns explicitly; the DTO copies these exact facts, not database defaults.
        originalChapter = new com.chengyinhub.business.domain.CmsTopicChapter();
        originalChapter.setId(11L); originalChapter.setTopicId(TOPIC); originalChapter.setName("第一章");
        originalChapter.setRecruitEnabled(0); originalChapter.setRequired(1);
        originalChapter.setTermsMode(com.chengyinhub.common.constant.TermsMode.PERK); originalChapter.setMaxMerchant(1);
        writtenChapters.add(originalChapter);
        com.chengyinhub.business.domain.CmsTopicNode originalNode = new com.chengyinhub.business.domain.CmsTopicNode();
        originalNode.setId(12L); originalNode.setTopicId(TOPIC); originalNode.setChapterId(11L);
        originalNode.setStatus(1L); originalNode.setName("第一站"); originalNode.setAddress("人民广场"); originalNode.setNodeTime(30);
        writtenNodes.add(originalNode);
        when(chapterMapper.deleteCmsTopicChapterById(anyLong())).thenAnswer(call ->
                writtenChapters.removeIf(row -> call.getArgument(0).equals(row.getId())) ? 1 : 0);
        when(nodeMapper.deleteCmsTopicNodeById(anyLong())).thenAnswer(call ->
                writtenNodes.removeIf(row -> call.getArgument(0).equals(row.getId())) ? 1 : 0);
        when(chapterMapper.selectByTopicForUpdate(anyLong())).thenAnswer(call -> writtenChapters.stream()
                .filter(row -> call.getArgument(0).equals(row.getTopicId())).collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        when(nodeMapper.selectByTopicForUpdate(anyLong())).thenAnswer(call -> writtenNodes.stream()
                .filter(row -> call.getArgument(0).equals(row.getTopicId())).collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        when(chapterMapper.selectCmsTopicChapterList(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicChapter query = call.getArgument(0);
            if (query == null) return Collections.emptyList();
            return writtenChapters.stream().filter(row -> java.util.Objects.equals(query.getTopicId(), row.getTopicId()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        });
        when(nodeMapper.selectCmsTopicNodeList(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicNode query = call.getArgument(0);
            if (query == null) return Collections.emptyList();
            return writtenNodes.stream().filter(row -> java.util.Objects.equals(query.getTopicId(), row.getTopicId()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        });
        java.util.concurrent.atomic.AtomicLong chapterIds = new java.util.concurrent.atomic.AtomicLong(100L);
        java.util.concurrent.atomic.AtomicLong nodeIds = new java.util.concurrent.atomic.AtomicLong(200L);
        when(chapterMapper.insertCmsTopicChapter(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicChapter row = call.getArgument(0);
            row.setId(chapterIds.incrementAndGet()); writtenChapters.add(row);
            return 1;
        });
        when(nodeMapper.insertCmsTopicNode(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicNode row = call.getArgument(0);
            row.setId(nodeIds.incrementAndGet()); writtenNodes.add(row);
            return 1;
        });
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        java.util.concurrent.atomic.AtomicLong activityIds = new java.util.concurrent.atomic.AtomicLong(300L);
        when(activityMapper.insertCmsActivity(any())).thenAnswer(call -> {
            ((CmsActivity) call.getArgument(0)).setId(activityIds.incrementAndGet());
            return 1;
        });
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        auditTaskService = mock(IAuditTaskService.class);
        canonicalMutationService = mock(ITopicSaleCanonicalMutationService.class);
        commercialReviewService = mock(ITopicCommercialReviewService.class);

        svc = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(svc, "supplyReviewService", mock(
                com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? topicMapper.selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(svc, "quotaService", mock(IQuotaService.class));
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
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(svc, "omsTicketService", mock(IOmsTicketService.class));
        ReflectionTestUtils.setField(svc, "canonicalMutationService", canonicalMutationService);
        ReflectionTestUtils.setField(svc, "topicCommercialReviewService", commercialReviewService);
        when(registrationMapper.selectCmsRegistrationList(any())).thenReturn(Collections.<CmsRegistration>emptyList());
        when(activityMapper.selectCmsActivityList(any())).thenReturn(Collections.<CmsActivity>emptyList());
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);

        // 驳回档(status=2)⇒ 全量重建;生命周期 PRICING,自玩价 5 元
        stored = new CmsTopic();
        stored.setId(TOPIC);
        stored.setMemberId(ME);
        stored.setProductType(ProductType.ORIENTEERING);
        stored.setPublishMode("pro");
        stored.setStatus(2);
        stored.setLifecycle(TopicLifecycleState.PRICING.getCode());
        stored.setDelFlag(0);
        stored.setStartDate(daysFromNow(3));
        stored.setEndDate(daysFromNow(5));
        stored.setName("旧名字");
        stored.setSelfPlay(1);
        stored.setSelfPlayPrice(new BigDecimal("5"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(stored);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(stored);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(0);
        doAnswer(invocation -> {
            CmsTopic patch = invocation.getArgument(0);
            if (patch.getName() != null) stored.setName(patch.getName());
            if (patch.getSelfPlayPrice() != null) stored.setSelfPlayPrice(patch.getSelfPlayPrice());
            if (patch.getProductType() != null) stored.setProductType(patch.getProductType());
            if (patch.getPublishMode() != null) stored.setPublishMode(patch.getPublishMode());
            if (patch.getCheckStatus() != null) stored.setCheckStatus(patch.getCheckStatus());
            if (patch.getThemeRuntimeConfigJson() != null) stored.setThemeRuntimeConfigJson(patch.getThemeRuntimeConfigJson());
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
        com.chengyinhub.business.mapper.ThemeStateKeyMapper keys =
                mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class);
        when(keys.lockTopicForStateKeyWrite(TOPIC)).thenAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            org.junit.jupiter.api.Assertions.assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(publicationDataSource));
            return topicMapper.selectCmsTopicByIdForUpdate(TOPIC).getId();
        });
        when(keys.selectByTopicIdForUpdate(TOPIC)).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(svc, "themeStateKeyService",
                new com.chengyinhub.business.service.support.ThemeStateKeyService(keys));
        svc = com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(svc);
        org.springframework.jdbc.datasource.DataSourceTransactionManager transactions =
                (org.springframework.jdbc.datasource.DataSourceTransactionManager)
                com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(svc, "transactionManager");
        publicationDataSource = transactions.getDataSource();
    }

    private java.util.Map<String,Object> frozenSaleTerms() {
        com.chengyinhub.business.service.support.CommercialTopicPolicy policy =
                (com.chengyinhub.business.service.support.CommercialTopicPolicy)
                ReflectionTestUtils.getField(svc, "commercialTopicPolicy");
        return com.chengyinhub.business.service.support.TopicSaleTerms.of(
                policy.saleParameters(stored, policy.evaluate(TOPIC)));
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
        chapter.setRecruitEnabled(originalChapter.getRecruitEnabled());
        chapter.setRequired(originalChapter.getRequired());
        chapter.setTermsMode(originalChapter.getTermsMode());
        chapter.setMaxMerchant(originalChapter.getMaxMerchant());
        chapter.setNodes(new ArrayList<>(Arrays.asList(node)));
        dto.setChapters(new ArrayList<>(Arrays.asList(chapter)));
        dto.setTickets(new ArrayList<ActivityTicketRequest>());
        return dto;
    }

    @Test
    void storyAndNameEdit_keepsSaleReview_andRefreshesThePendingTask() {
        java.util.Map<String,Object> before = frozenSaleTerms();
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).updateTopicByOwner(dto("新名字", null, "5"), ME, "me");

        org.junit.jupiter.api.Assertions.assertEquals(before, frozenSaleTerms(), "same-price content edits must preserve the full sale-terms fingerprint");
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("5"), stored.getSelfPlayPrice());
        verify(canonicalMutationService, never()).invalidateApprovedCanonical(anyLong(), anyString());
        verify(commercialReviewService, times(1)).submitSaleReview(TOPIC, ME);
    }

    @Test
    void priceEdit_resetsSaleReview() {
        java.util.Map<String,Object> before = frozenSaleTerms();
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).updateTopicByOwner(dto("旧名字", null, "8"), ME, "me");

        org.junit.jupiter.api.Assertions.assertNotEquals(before, frozenSaleTerms(), "5 to 8 changes the original price fact");
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("8"), stored.getSelfPlayPrice());
        verify(canonicalMutationService, times(1)).invalidateApprovedCanonical(eq(TOPIC), eq("topic-content-rebuild"));
        verify(commercialReviewService, never()).submitSaleReview(anyLong(), anyLong());
    }
}
