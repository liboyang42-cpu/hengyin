package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.ITopicCommercialReviewService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.support.TopicBundleReviewService;
import com.chengyinhub.business.util.ChapterBlockReadSupport;
import com.chengyinhub.web.controller.api.ApiPlayProgressController;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.utils.DistanceCalculatorUtils;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通过公开 V2 写 seam 验证章节块编译、ID 翻译与旧写隔离。 */
class ChapterFlowWriteServiceTest
{
    private static final long TOPIC_ID = 71L;
    private static final long MEMBER_ID = 19L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private CmsTopicServiceImpl service;
    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicNodeMapper nodeMapper;
    private List<CmsTopicChapter> insertedChapters;
    private List<CmsTopicChapter> updatedChapters;
    private List<CmsTopicNode> insertedNodes;
    private List<CmsTopic> updatedTopics;
    private CmsTopic existing;
    private IAuditTaskService auditTaskService;
    private TopicBundleReviewService bundleReviewService;
    private com.chengyinhub.business.mapper.CmsMemberTemplateMapper memberTemplateMapper;

    @BeforeEach
    void setUp()
    {
        service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        insertedChapters = new ArrayList<>();
        updatedChapters = new ArrayList<>();
        insertedNodes = new ArrayList<>();
        updatedTopics = new ArrayList<>();
        List<CmsTopicChapter> currentChapters = new ArrayList<>();
        List<CmsTopicNode> currentNodes = new ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> new ArrayList<>(currentChapters))
                .when(chapterMapper).selectByTopicForUpdate(TOPIC_ID);
        org.mockito.Mockito.doAnswer(call -> new ArrayList<>(currentNodes))
                .when(nodeMapper).selectByTopicForUpdate(TOPIC_ID);
        org.mockito.Mockito.doAnswer(call -> new ArrayList<>(currentChapters))
                .when(chapterMapper).selectCmsTopicChapterList(any());
        org.mockito.Mockito.doAnswer(call -> new ArrayList<>(currentNodes))
                .when(nodeMapper).selectCmsTopicNodeList(any());
        org.mockito.Mockito.doAnswer(call -> currentChapters.removeIf(row -> row.getId().equals(call.getArgument(0))) ? 1 : 0)
                .when(chapterMapper).deleteCmsTopicChapterById(any());
        org.mockito.Mockito.doAnswer(call -> currentNodes.removeIf(row -> row.getId().equals(call.getArgument(0))) ? 1 : 0)
                .when(nodeMapper).deleteCmsTopicNodeById(any());
        AtomicLong chapterIds = new AtomicLong(700L);
        AtomicLong nodeIds = new AtomicLong(900L);

        existing = new CmsTopic();
        existing.setId(TOPIC_ID);
        existing.setMemberId(MEMBER_ID);
        existing.setStatus(0);
        existing.setLifecycle(0);
        existing.setDelFlag(0);
        existing.setMerchantStatus(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(existing);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(existing);
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(1);
        doAnswer(invocation -> {
            updatedTopics.add(invocation.getArgument(0));
            return 1;
        }).when(topicMapper).updateCmsTopic(any());

        doAnswer(invocation -> {
            CmsTopicChapter chapter = invocation.getArgument(0);
            chapter.setId(chapterIds.incrementAndGet());
            insertedChapters.add(chapter);
            currentChapters.add(chapter);
            return 1;
        }).when(chapterMapper).insertCmsTopicChapter(any());
        doAnswer(invocation -> {
            CmsTopicChapter chapter = invocation.getArgument(0);
            updatedChapters.add(chapter);
            insertedChapters.stream().filter(row -> row.getId().equals(chapter.getId())).forEach(row -> {
                if (chapter.getBlocksJson() != null) row.setBlocksJson(chapter.getBlocksJson());
            });
            return 1;
        }).when(chapterMapper).updateCmsTopicChapter(any());
        doAnswer(invocation -> {
            CmsTopicNode node = invocation.getArgument(0);
            node.setId(nodeIds.incrementAndGet());
            insertedNodes.add(node);
            currentNodes.add(node);
            return 1;
        }).when(nodeMapper).insertCmsTopicNode(any());

        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        when(registrationMapper.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        when(activityMapper.selectCmsActivityList(any())).thenReturn(Collections.emptyList());

        // 本类的 update(...) 固定带 openMerchantPool=1(见 update()),会走到招商资格闸
        // CmsTopicServiceImpl.resolveMerchantPoolFlag ⇒ 必须给它一个有效的主理人档,
        // 否则这些「测章节流」的用例会被那道闸拒掉,失败原因与被测对象无关。
        // 闸本身的行为由 CmsTopicMerchantPoolRoleGuardTest 覆盖,这里只是让它放行。
        IClubLeaderService clubLeaderService = mock(IClubLeaderService.class);
        ClubLeader activeLeader = new ClubLeader();
        activeLeader.setStatus(1);
        when(clubLeaderService.selectClubLeaderByMemberId(any())).thenReturn(activeLeader);
        set("clubLeaderService", clubLeaderService);

        set("cmsTopicMapper", topicMapper);
        set("cmsTopicChapterMapper", chapterMapper);
        set("cmsTopicNodeMapper", nodeMapper);
        set("omsTicketMapper", mock(OmsTicketMapper.class));
        set("cmsCollaboratorsMapper", mock(CmsCollaboratorsMapper.class));
        set("cmsActivityMapper", activityMapper);
        set("cmsRegistrationMapper", registrationMapper);
        set("couponRewardAuthorizationService", mock(ICouponRewardAuthorizationService.class));
        set("clubService", mock(IClubService.class));
        set("xpBudgetService", mock(IXpBudgetService.class));
        auditTaskService = mock(IAuditTaskService.class);
        bundleReviewService = mock(TopicBundleReviewService.class);
        set("auditTaskService", auditTaskService);
        set("quotaService", mock(com.chengyinhub.business.service.IQuotaService.class));
        set("settlementDebtLedger", mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        when(topicMapper.publishRouteSnapshot(any(), any(), any())).thenReturn(1);
        set("topicBundleReviewService", bundleReviewService);
        set("topicCommercialReviewService", mock(ITopicCommercialReviewService.class));
        set("commercialTopicPolicy", new com.chengyinhub.business.service.support.CommercialTopicPolicy(
                (OmsTicketMapper) ReflectionTestUtils.getField(service, "omsTicketMapper"), chapterMapper,
                mock(com.chengyinhub.business.mapper.MerchantChapterOfferMapper.class), topicMapper));
        set("omsTicketService", mock(IOmsTicketService.class));
        set("distanceCalculatorUtils", mock(DistanceCalculatorUtils.class));
        set("sysCategoryService", mock(ISysCategoryService.class));
        memberTemplateMapper = mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class);
        set("cmsMemberTemplateMapper", memberTemplateMapper);
        // Actual media schema/reference admission; only reviewed persistence is a fixture.
        com.chengyinhub.business.mapper.AppMediaAssetMapper assets = mock(com.chengyinhub.business.mapper.AppMediaAssetMapper.class);
        for (long mediaId : new long[] {901L, 902L, 903L}) when(assets.select(mediaId)).thenReturn(java.util.Map.of(
                "id", mediaId, "ownerType", "APP", "memberId", 19L, "reviewState", "APPROVED"));
        com.chengyinhub.business.service.support.AppMediaReferenceGuard references =
                new com.chengyinhub.business.service.support.AppMediaReferenceGuard();
        ReflectionTestUtils.setField(references, "mapper", assets);
        com.chengyinhub.business.service.support.AppMediaPublicationGuard media =
                new com.chengyinhub.business.service.support.AppMediaPublicationGuard();
        ReflectionTestUtils.setField(media, "references", references);
        ReflectionTestUtils.setField(media, "topics", topicMapper);
        ReflectionTestUtils.setField(media, "chapters", chapterMapper);
        ReflectionTestUtils.setField(media, "nodes", nodeMapper);
        ReflectionTestUtils.setField(media, "templates", memberTemplateMapper);
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", media);
        set("themeStateKeyService", new com.chengyinhub.business.service.support.ThemeStateKeyService(
                mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class)));
        // The current production owner implements V2 writes directly; all collaborators are wired above.
    }

    @Test
    void storyGameStoresExplicitLocationIntentAndRejectsMissingTemplateOrCoordinates() throws Exception {
        NodeDTO game = node("角色建档");
        game.setTemplateId(100L);
        ChapterBlockDTO block = nodeBlock(0);
        block.setLocationRequired(false);
        ChapterDTO chapter = chapter("出生", game);
        chapter.setBlocks(Arrays.asList(text("你的名字是"), block));
        ChapterFlowCompiler.CompiledFlow flow = ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter));
        String stored = flow.getChapters().get(0).materializeBlocks(Collections.singletonList(900L));
        assertFalse(JSON.readTree(stored).get(1).get("locationRequired").asBoolean());
        game.setTemplateId(null);
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
        game.setTemplateId(100L);
        game.setLongitude("121.4");
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
        game.setLongitude(null);
        chapter.getBlocks().get(0).setLocationRequired(false);
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
    }

    @Test
    void storyGameRejectsArrivalTemplateBeforeReplacingChapters() {
        com.chengyinhub.business.domain.CmsMemberTemplate template =
                new com.chengyinhub.business.domain.CmsMemberTemplate();
        template.setId(100L);
        template.setMemberId(MEMBER_ID);
        template.setStatus(1L);
        template.setDraftStatus(1);
        template.setValidationMethod(5);
        when(memberTemplateMapper.selectCmsMemberTemplateById(100L)).thenReturn(template);
        NodeDTO game = node("故事玩法");
        game.setTemplateId(100L);
        ChapterDTO chapter = chapter("出生", game);
        ChapterBlockDTO block = nodeBlock(0);
        block.setLocationRequired(false);
        chapter.setBlocks(Collections.singletonList(block));
        assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester"));
        verify(chapterMapper, never()).insertCmsTopicChapter(any());
        verify(nodeMapper, never()).insertCmsTopicNode(any());
    }

    @Test
    void submittedV2EditFreezesAfterRebuildAndRefreshesOneTopicTask() {
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);
        TopicBundleReviewService.Submission submission =
                new TopicBundleReviewService.Submission("{\"schemaVersion\":2}", Collections.singletonList(100L));
        when(bundleReviewService.freezeForSubmission(TOPIC_ID, MEMBER_ID)).thenReturn(submission);
        when(auditTaskService.submitOrRefreshTopicBundle(TOPIC_ID, MEMBER_ID, submission.getSnapshotJson()))
                .thenReturn(91L);
        com.chengyinhub.business.domain.AuditTask pending = new com.chengyinhub.business.domain.AuditTask();
        pending.setId(91L);
        pending.setStatus(com.chengyinhub.business.domain.AuditTask.STATUS_PENDING);
        when(auditTaskService.selectAuditTaskById(91L)).thenReturn(pending);
        com.chengyinhub.business.domain.CmsMemberTemplate draft =
                new com.chengyinhub.business.domain.CmsMemberTemplate();
        draft.setId(100L);
        draft.setMemberId(MEMBER_ID);
        draft.setDraftStatus(0);
        draft.setStatus(0L);
        draft.setCheckStatus(0);
        draft.setPublishStatus(0L);
        draft.setDelFlag(0);
        when(memberTemplateMapper.selectCmsMemberTemplateById(100L)).thenReturn(draft);
        NodeDTO draftNode = node("旧码头");
        draftNode.setTemplateId(100L);
        ChapterDTO chapter = chapter("第一章", draftNode);
        chapter.setBlocks(Collections.singletonList(nodeBlock(0)));

        // Observe admission at the first actual graph write, preserving the insert Answer.
        // This base has no separate lockTopicContentBudget mapper contract.
        var originalInsert = org.mockito.Mockito.mockingDetails(chapterMapper).getStubbings().stream()
                .filter(stub -> "insertCmsTopicChapter".equals(stub.getInvocation().getMethod().getName()))
                .findFirst().orElseThrow();
        java.util.concurrent.atomic.AtomicBoolean admissionObserved = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> {
            if (admissionObserved.compareAndSet(false, true))
                verify(topicMapper, times(2)).selectCmsTopicByIdForUpdate(TOPIC_ID);
            return originalInsert.answer(call);
        }).when(chapterMapper).insertCmsTopicChapter(any());

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        verify(bundleReviewService).freezeForSubmission(TOPIC_ID, MEMBER_ID);
        verify(auditTaskService).submitOrRefreshTopicBundle(TOPIC_ID, MEMBER_ID, submission.getSnapshotJson());
        assertTrue(admissionObserved.get(), "必须实际写入章节，且写入前已完成两次准入锁");
        org.mockito.InOrder lockOrder = inOrder(auditTaskService, topicMapper);
        lockOrder.verify(topicMapper).selectCmsTopicByIdForUpdate(TOPIC_ID);
        lockOrder.verify(auditTaskService).lockOpenTopicBundleTask(TOPIC_ID);
        lockOrder.verify(topicMapper).selectCmsTopicByIdForUpdate(TOPIC_ID);
        assertTrue(updatedTopics.stream().anyMatch(topic -> Integer.valueOf(1).equals(topic.getStatus())
                        && Integer.valueOf(0).equals(topic.getCheckStatus())
                        && Integer.valueOf(1).equals(topic.getUserStatus())),
                "主题发布即上架，审核任务留在后台");
    }

    @Test
    void escalatedAuditTaskReadbackIsReportedAsEscalated() {
        stubBundleSubmission();
        stubAuditTaskReadback(com.chengyinhub.business.domain.AuditTask.STATUS_ESCALATED);
        NodeDTO draftNode = node("旧码头");
        draftNode.setTemplateId(100L);
        ChapterDTO chapter = chapter("第一章", draftNode);
        chapter.setBlocks(Collections.singletonList(nodeBlock(0)));

        com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO result =
                service.updateTopicByOwnerV2WithReview(update(chapter), MEMBER_ID, "tester");

        assertEquals("ESCALATED", result.getReviewState());
        assertEquals(Long.valueOf(91L), result.getAuditTaskId());
    }

    /** 负控:去掉 submitTopicBundle 里「回读审核任务状态」的守卫 ⇒ 本用例红(不再抛「审核任务状态读取失败」)。 */
    @Test
    void auditTaskReadbackThatIsNotAnOpenTaskFailsTheSubmission() {
        stubBundleSubmission();
        NodeDTO draftNode = node("旧码头");
        draftNode.setTemplateId(100L);
        ChapterDTO chapter = chapter("第一章", draftNode);
        chapter.setBlocks(Collections.singletonList(nodeBlock(0)));

        // 回读为 null(读不到任务)
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> service.updateTopicByOwnerV2WithReview(update(chapter), MEMBER_ID, "tester"));
        assertEquals("审核任务状态读取失败", missing.getMessage());

        // 回读为终态(已过审)同样不能当成提交成功
        stubAuditTaskReadback(com.chengyinhub.business.domain.AuditTask.STATUS_APPROVED);
        IllegalStateException finished = assertThrows(IllegalStateException.class,
                () -> service.updateTopicByOwnerV2WithReview(update(chapter), MEMBER_ID, "tester"));
        assertEquals("审核任务状态读取失败", finished.getMessage());
    }

    /** 组合提审跑到「回读审核任务状态」之前的全部装配;返回冻结的快照。 */
    private TopicBundleReviewService.Submission stubBundleSubmission() {
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);
        TopicBundleReviewService.Submission submission =
                new TopicBundleReviewService.Submission("{\"schemaVersion\":2}", Collections.singletonList(100L));
        when(bundleReviewService.freezeForSubmission(TOPIC_ID, MEMBER_ID)).thenReturn(submission);
        when(auditTaskService.submitOrRefreshTopicBundle(TOPIC_ID, MEMBER_ID, submission.getSnapshotJson()))
                .thenReturn(91L);
        com.chengyinhub.business.domain.CmsMemberTemplate draft =
                new com.chengyinhub.business.domain.CmsMemberTemplate();
        draft.setId(100L);
        draft.setMemberId(MEMBER_ID);
        draft.setDraftStatus(0);
        draft.setStatus(0L);
        draft.setCheckStatus(0);
        draft.setPublishStatus(0L);
        draft.setDelFlag(0);
        when(memberTemplateMapper.selectCmsMemberTemplateById(100L)).thenReturn(draft);
        return submission;
    }

    private void stubAuditTaskReadback(Integer status) {
        com.chengyinhub.business.domain.AuditTask task = new com.chengyinhub.business.domain.AuditTask();
        task.setId(91L);
        task.setStatus(status);
        when(auditTaskService.selectAuditTaskById(91L)).thenReturn(task);
    }

    @Test
    void submittedSimpleDraftPublishesWhileSavingItKeepsItPrivate() {
        // Keep the same story and draft/publish assertions; a formal snapshot also needs
        // one real playable station, persisted by the original graph mapper Answers.
        ChapterDTO chapter = chapter("第一章", node("发布测试站点"));
        chapter.setBlocks(Arrays.asList(text("发布测试故事"), nodeBlock(0)));
        TopicUpdateDTO dto = update(chapter);
        dto.setPublishMode("simple");
        dto.setSaveDraft(1);
        service.updateTopicByOwnerV2WithReview(dto, MEMBER_ID, "tester");
        assertFalse(updatedTopics.stream().anyMatch(topic -> Integer.valueOf(1).equals(topic.getStatus())
                && Integer.valueOf(1).equals(topic.getUserStatus())));
        verify(topicMapper, never()).updateTopicDraftState(TOPIC_ID, 0);

        updatedTopics.clear();
        when(topicMapper.updateTopicDraftState(TOPIC_ID, 0)).thenReturn(1);
        dto.setSaveDraft(0);
        service.updateTopicByOwnerV2WithReview(dto, MEMBER_ID, "tester");

        verify(topicMapper).updateTopicDraftState(TOPIC_ID, 0);
        assertTrue(updatedTopics.stream().anyMatch(topic -> Integer.valueOf(1).equals(topic.getStatus())
                && Integer.valueOf(1).equals(topic.getUserStatus())));
        verify((com.chengyinhub.business.service.IQuotaService)
                ReflectionTestUtils.getField(service, "quotaService"))
                .lockAndCheckThemeOnline(MEMBER_ID, TOPIC_ID);
        verify(topicMapper).publishRouteSnapshot(any(), any(), any());
        verify(auditTaskService, never()).submitOrRefreshTopicBundle(any(), any(), any());
    }

    @Test
    void openBundleReviewCannotBeBypassedBySwitchingToSimpleMode() {
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);
        when(auditTaskService.lockOpenTopicBundleTask(TOPIC_ID)).thenReturn(true);
        TopicUpdateDTO dto = update(legacyChapter());
        dto.setPublishMode("simple");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.updateTopicByOwnerV2(dto, MEMBER_ID, "tester"));

        assertTrue(error.getMessage().contains("审核中"));
        verify(bundleReviewService, never()).freezeForSubmission(any(), any());
    }

    @Test
    void simpleV2PublishCannotBypassReviewWithAPrivateDraftTemplate() {
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);
        com.chengyinhub.business.domain.CmsMemberTemplate draft =
                new com.chengyinhub.business.domain.CmsMemberTemplate();
        draft.setId(100L);
        draft.setMemberId(MEMBER_ID);
        draft.setDraftStatus(0);
        draft.setStatus(0L);
        draft.setCheckStatus(0);
        draft.setPublishStatus(0L);
        draft.setDelFlag(0);
        when(memberTemplateMapper.selectCmsMemberTemplateById(100L)).thenReturn(draft);
        NodeDTO draftNode = node("旧码头");
        draftNode.setTemplateId(100L);
        ChapterDTO chapter = chapter("第一章", draftNode);
        chapter.setBlocks(Collections.singletonList(nodeBlock(0)));
        TopicUpdateDTO dto = update(chapter);
        dto.setPublishMode("simple");

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.updateTopicByOwnerV2(dto, MEMBER_ID, "tester"));

        assertTrue(error.getMessage().contains("尚未通过审核") || error.getMessage().contains("草稿模板"),
                error.getMessage());
        verify(auditTaskService, never()).submitOrRefreshTopicBundle(any(), any(), any());
    }

    @Test
    void nonBlockSimpleV2PublishCannotBypassReviewWithAPrivateDraftTemplate() {
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);
        existing.setProductType(ProductType.FREE_EXPLORE);
        com.chengyinhub.business.domain.CmsMemberTemplate draft =
                new com.chengyinhub.business.domain.CmsMemberTemplate();
        draft.setId(100L);
        draft.setMemberId(MEMBER_ID);
        draft.setDraftStatus(0);
        draft.setStatus(0L);
        draft.setCheckStatus(0);
        draft.setPublishStatus(0L);
        draft.setDelFlag(0);
        when(memberTemplateMapper.selectCmsMemberTemplateById(100L)).thenReturn(draft);
        ChapterDTO chapter = legacyChapter();
        chapter.getNodes().get(0).setTemplateId(100L);
        TopicUpdateDTO dto = update(chapter);
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("simple");
        dto.setRecruitDeadline(new Date(System.currentTimeMillis() + 3600_000L));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.updateTopicByOwnerV2(dto, MEMBER_ID, "tester"));

        assertTrue(error.getMessage().contains("尚未通过审核") || error.getMessage().contains("草稿模板"),
                error.getMessage());
        verify(auditTaskService, never()).submitOrRefreshTopicBundle(any(), any(), any());
    }

    @Test
    void blockOrderDrivesGlobalSortAndSameChapterNodeIds() throws Exception
    {
        ChapterDTO first = chapter("第一章", node("甲"), node("乙"));
        first.setBlocks(Arrays.asList(text("开场"), nodeBlock(1), text("过场"), nodeBlock(0)));
        ChapterDTO second = chapter("第二章", node("丙"), node("丁"));
        second.setBlocks(Arrays.asList(nodeBlock(0), text("章间"), nodeBlock(1)));
        first.getNodes().get(0).setLongitude("0");
        first.getNodes().get(0).setLatitude("0");
        first.getNodes().get(1).setLongitude("10");
        first.getNodes().get(1).setLatitude("0");
        second.getNodes().get(0).setLongitude("20");
        second.getNodes().get(0).setLatitude("0");
        second.getNodes().get(1).setLongitude("30");
        second.getNodes().get(1).setLatitude("0");
        DistanceCalculatorUtils distance = new DistanceCalculatorUtils();
        set("distanceCalculatorUtils", distance);

        service.updateTopicByOwnerV2(update(first, second), MEMBER_ID, "tester");

        assertEquals(Arrays.asList(2L, 1L, 3L, 4L), Arrays.asList(
                insertedNodes.get(0).getSortId(), insertedNodes.get(1).getSortId(),
                insertedNodes.get(2).getSortId(), insertedNodes.get(3).getSortId()),
                "节点 sortId 必须按章节数组序→块序全局连续派生，不得照 nodes 数组序");
        assertEquals(Arrays.asList(1L, 2L), Arrays.asList(
                insertedChapters.get(0).getSortId(), insertedChapters.get(1).getSortId()),
                "章节顺序只由 chapters 数组派生");
        double expectedMileage = distance.calculateDistance("10", "0", "0", "0")
                + distance.calculateDistance("0", "0", "20", "0")
                + distance.calculateDistance("20", "0", "30", "0");
        BigDecimal actualMileage = updatedTopics.get(0).getTotalMileage();
        assertEquals(0, actualMileage.compareTo(BigDecimal.valueOf(expectedMileage)),
                "V2 total_mileage 必须按 blocks 派生顺序计算，不能继续照 nodes 数组序");
        for (CmsTopicNode node : insertedNodes)
        {
            assertNotNull(node.getSortId(), "块化路径不得留下 null sortId，否则 isPrevDone 会直接放行");
        }

        List<Map<String, Object>> firstStored = storedBlocks(updatedChapters.get(0));
        List<Map<String, Object>> secondStored = storedBlocks(updatedChapters.get(1));
        assertEquals(insertedNodes.get(1).getId(), longValue(firstStored.get(1).get("nodeId")),
                "第一个 node 块必须翻译成本章 nodes[1] 刚生成的 id");
        assertEquals(insertedNodes.get(0).getId(), longValue(firstStored.get(3).get("nodeId")),
                "第二个 node 块必须翻译成本章 nodes[0] 刚生成的 id");
        assertEquals(insertedNodes.get(2).getId(), longValue(secondStored.get(0).get("nodeId")),
                "跨章不能借用上一章的 nodeId");
        assertEquals(insertedNodes.get(3).getId(), longValue(secondStored.get(2).get("nodeId")),
                "每个持久化 nodeId 都必须来自同章本次插入结果");

        ICmsTopicNodeService nodeRead = mock(ICmsTopicNodeService.class);
        IPlayerNodeProgressService progressRead = mock(IPlayerNodeProgressService.class);
        when(nodeRead.selectCmsTopicNodeList(any())).thenReturn(insertedNodes);
        when(progressRead.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        ApiPlayProgressController guard = new ApiPlayProgressController();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(guard, "cmsTopicNodeService", nodeRead);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(guard, "playerNodeProgressService", progressRead);
        Boolean blocked = com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(guard, "isPrevDone", MEMBER_ID, 501L,
                insertedNodes.get(0));
        assertEquals(Boolean.FALSE, blocked,
                "blocks 中排在当前节点之前的节点未完成时，isPrevDone 必须拒绝跳关");

        PlayerNodeProgress completed = new PlayerNodeProgress();
        completed.setNodeId(insertedNodes.get(1).getId());
        // 拍照人工审核(2026-09-15 裁决第9条)后 isPrevDone 只认 status==1 为完成事实,
        // 待审行(status=0)不得放行下一站——这里显式钉住「真完成」这个前提,不再让默认 null 蒙混过关。
        completed.setStatus(1);
        when(progressRead.selectPlayerNodeProgressList(any()))
                .thenReturn(Collections.singletonList(completed));
        Boolean allowed = com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(guard, "isPrevDone", MEMBER_ID, 501L,
                insertedNodes.get(0));
        assertEquals(Boolean.TRUE, allowed,
                "派生顺序中的真实前置节点完成后，isPrevDone 才能放行当前节点");
    }

    @Test
    void descriptionIsOnlyTextBeforeFirstNodeAndNewlinesStayInsideTextBlocks() throws Exception
    {
        ChapterDTO chapter = chapter("投影章", node("关卡"));
        chapter.setBlocks(Arrays.asList(
                text("第一段\n第二段"), text("相邻文本块"), nodeBlock(0), text("锁后正文")));

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        assertEquals("第一段\n第二段\n相邻文本块", insertedChapters.get(0).getDescription(),
                "description 只投影首个 node 前的全部 text，块间用换行隔开");
        List<Map<String, Object>> stored = storedBlocks(updatedChapters.get(0));
        assertEquals(4, stored.size(), "相邻 text 块允许存在，后端不得合并或拆分");
        assertEquals("第一段\n第二段", stored.get(0).get("content"),
                "text 块内部换行必须原样保留，仍由读侧按换行拆段");
        assertEquals("锁后正文", stored.get(3).get("content"),
                "description 投影不得反向删改 blocks 正文");
    }

    @Test
    void narrativeBlocksBindToStationAndStayOutOfDescription() throws Exception
    {
        ChapterDTO chapter = chapter("叙事章", node("甲站"), node("乙站"));
        chapter.setBlocks(Arrays.asList(
                beat("brief", "question", 0, "甲站委托"),
                beat("enter", "opener", 0, "甲站开场"),
                nodeBlock(0),
                beat("outcome", "line", 0, "甲站结论"),
                beat("enter", "opener", 1, "乙站开场"),
                nodeBlock(1),
                beat("outcome", "line", 1, "乙站结论"),
                beat("deliver", "reward", 1, "乙站纪念章")));

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        List<Map<String, Object>> stored = storedBlocks(updatedChapters.get(0));
        assertEquals(8, stored.size(), "叙事块原样保留,不与 node 块合并");
        assertEquals(insertedNodes.get(0).getId(), longValue(stored.get(0).get("nodeId")),
                "甲站委托绑到本章 nodes[0] 落库 id");
        assertEquals(insertedNodes.get(1).getId(), longValue(stored.get(4).get("nodeId")),
                "乙站开场绑到本章 nodes[1] 落库 id,不串到甲站");
        assertEquals("line", stored.get(3).get("field"), "物化保留 beat 与 field");
        assertEquals("甲站结论", stored.get(3).get("content"));
        assertEquals("", insertedChapters.get(0).getDescription(),
                "叙事块是站点正文,不进章节列表摘要");
    }

    @Test
    void narrativeQuestionWithoutAnswerIsRejectedAtSave()
    {
        ChapterDTO chapter = chapter("坏问答", node("甲站"));
        ChapterBlockDTO question = beat("enter", "q", 0, "问?");
        question.setQid("night");
        chapter.setBlocks(Arrays.asList(question, nodeBlock(0)));

        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
        assertTrue(error.getMessage().contains("成对"), error.getMessage());
    }

    @Test
    void nodeIndexAtNodesLengthHitsTheBoundsGate()
    {
        ChapterDTO chapter = chapter("越界章", node("唯一节点"));
        chapter.setBlocks(Collections.singletonList(nodeBlock(chapter.getNodes().size())));

        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));

        assertTrue(error.getMessage().contains("nodeIndex 越界"),
                "必须由 I3 下标闸拒绝，不能被相邻的漏引用闸接住: " + error.getMessage());
    }

    @Test
    void duplicateNodeIndexHitsTheUniquenessGate()
    {
        ChapterDTO chapter = chapter("重复章", node("唯一节点"));
        chapter.setBlocks(Arrays.asList(nodeBlock(0), nodeBlock(0)));

        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester"));

        assertTrue(error.getMessage().contains("重复引用"),
                "必须由 I2 重复引用闸拒绝: " + error.getMessage());
    }

    @Test
    void orphanNodeHitsTheCoverageGate()
    {
        ChapterDTO chapter = chapter("孤儿章", node("已引用"), node("孤儿"));
        chapter.setBlocks(Collections.singletonList(nodeBlock(0)));

        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));

        assertTrue(error.getMessage().contains("未被 blocks 引用") && error.getMessage().contains("nodeIndex=1"),
                "必须由 I4 孤儿覆盖闸指认具体下标: " + error.getMessage());
    }

    @Test
    void optionalChapterCannotEnterStoryFlow()
    {
        ChapterDTO chapter = chapter("可选章", node("节点"));
        chapter.setRequired(0);
        chapter.setBlocks(Collections.singletonList(nodeBlock(0)));

        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));

        assertTrue(error.getMessage().contains("required 必须为 1"),
                "必须由 I7 必选章闸拒绝: " + error.getMessage());
    }

    @Test
    void omittedRequiredIsMaterializedAsRequired()
    {
        ChapterDTO chapter = chapter("默认必选", node("节点"));
        chapter.setRequired(null);
        chapter.setBlocks(Collections.singletonList(nodeBlock(0)));

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        assertEquals(Integer.valueOf(1), insertedChapters.get(0).getRequired(),
                "进入故事流的章节必须强制落 required=1，不能依赖数据库隐式默认");
    }

    @Test
    void freeExploreV2MaterializesBlocks()
    {
        ChapterDTO chapter = chapter("自由探索章", node("节点"));
        chapter.setBlocks(Collections.singletonList(nodeBlock(0)));
        TopicUpdateDTO dto = update(chapter);
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setRecruitDeadline(new Date(System.currentTimeMillis() + 3600_000L));

        service.updateTopicByOwnerV2(dto, MEMBER_ID, "tester");

        assertEquals(1, insertedChapters.size());
        assertEquals(1, updatedChapters.size());
        assertNotNull(updatedChapters.get(0).getBlocksJson(), "自由探索 V2 故事流必须物化 blocks");
    }

    @Test
    void freeExploreV2RequestWithoutBlocksKeepsLegacyShape()
    {
        ChapterDTO chapter = legacyChapter();
        TopicUpdateDTO dto = update(chapter);
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setRecruitDeadline(new Date(System.currentTimeMillis() + 3600_000L));

        service.updateTopicByOwnerV2(dto, MEMBER_ID, "tester");

        assertEquals(1, insertedChapters.size());
        assertNull(insertedChapters.get(0).getBlocksJson(),
                "I6 要求自由探索继续走旧章节形态，即使调用方使用 V2 主题保存入口");
        assertTrue(updatedChapters.isEmpty(), "自由探索不得执行 blocks 写回");
    }

    @Test
    void legacyPathPersistsNoBlocks()
    {
        ChapterDTO legacy = chapter("旧版章", node("旧节点"));
        legacy.setDescription("旧版全文");
        legacy.setSchemaVersion(null);
        legacy.setBlocks(null);

        service.updateTopicByOwner(update(legacy), MEMBER_ID, "tester");

        assertEquals(1, insertedChapters.size());
        assertNull(insertedChapters.get(0).getBlocksJson(),
                "现有保存路径必须保持 blocks 为空，A10 延后依赖这条事实");
        assertTrue(updatedChapters.isEmpty(), "旧路径不得执行 blocks 写回");
    }

    @Test
    void legacyPathRefusesToOverwriteMaterializedTopic()
    {
        CmsTopicChapter materialized = new CmsTopicChapter();
        materialized.setId(88L);
        materialized.setTopicId(TOPIC_ID);
        materialized.setBlocksJson("[]");
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(materialized));

        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwner(update(legacyChapter()), MEMBER_ID, "tester"));

        assertTrue(error.getMessage().contains("请更新小程序"),
                "必须命中 §8.3 老客户端写守卫: " + error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any());
        verify(chapterMapper, never()).deleteCmsTopicChapterById(any());
    }

    @Test
    void legacyEndpointRejectsV2PayloadInsteadOfSilentlyDroppingIt()
    {
        ChapterDTO chapter = chapter("误投旧接口", node("节点"));
        chapter.setBlocks(Collections.singletonList(nodeBlock(0)));

        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwner(update(chapter), MEMBER_ID, "tester"));

        assertTrue(error.getMessage().contains("v2/update"),
                "块 payload 必须显式选择 V2 seam，旧接口不得静默吃掉: " + error.getMessage());
    }

    @Test
    void blockCountLimitRejectsInsteadOfTruncating()
    {
        ChapterDTO chapter = chapter("块数超限");
        chapter.setBlocks(new ArrayList<ChapterBlockDTO>());
        for (int i = 0; i <= ChapterFlowCompiler.MAX_BLOCKS_PER_CHAPTER; i++)
        {
            chapter.getBlocks().add(text(""));
        }

        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester"));

        assertTrue(error.getMessage().contains("块数超过"),
                "必须命中块数硬上限，不能静默截断: " + error.getMessage());
    }

    @Test
    void textLengthLimitRejectsInsteadOfTruncating()
    {
        ChapterDTO chapter = chapter("正文超限");
        chapter.setBlocks(Collections.singletonList(text(repeat("字", ChapterFlowCompiler.MAX_TEXT_LENGTH + 1))));

        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester"));

        assertTrue(error.getMessage().contains("正文超过"),
                "必须命中文本长度硬上限，不能静默截断: " + error.getMessage());
    }

    @Test
    void serializedSizeLimitUsesUtf8Bytes()
    {
        ChapterDTO chapter = chapter("体积超限", node("投影分界"));
        chapter.setBlocks(new ArrayList<ChapterBlockDTO>());
        chapter.getBlocks().add(nodeBlock(0));
        for (int i = 0; i < 5; i++)
        {
            chapter.getBlocks().add(text(repeat("中", ChapterFlowCompiler.MAX_TEXT_LENGTH)));
        }

        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester"));

        assertTrue(error.getMessage().contains("序列化后超过 64 KB"),
                "中文必须按 UTF-8 字节计 64 KB 上限: " + error.getMessage());
        assertFalse(updatedChapters.size() > 0, "超限不得写入截断后的 blocks");
    }

    @Test
    void exactPerItemLimitsRemainAccepted() throws Exception
    {
        ChapterDTO chapter = chapter("边界合法");
        chapter.setBlocks(new ArrayList<ChapterBlockDTO>());
        chapter.getBlocks().add(text(repeat("x", ChapterFlowCompiler.MAX_TEXT_LENGTH)));
        while (chapter.getBlocks().size() < ChapterFlowCompiler.MAX_BLOCKS_PER_CHAPTER)
        {
            chapter.getBlocks().add(text(""));
        }

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        assertEquals(ChapterFlowCompiler.MAX_BLOCKS_PER_CHAPTER,
                storedBlocks(updatedChapters.get(0)).size(),
                "等于硬上限仍应完整保留，不能 off-by-one 拒绝或截断");
    }

    @Test
    void descriptionProjectionOverColumnLimitIsRejectedBeforeWrite()
    {
        ChapterDTO chapter = chapter("投影超长");
        chapter.setBlocks(Arrays.asList(text(repeat("甲", 4000)), text(repeat("乙", 4000))));

        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester"));

        assertTrue(error.getMessage().contains("第 1 章")
                        && error.getMessage().contains("剧情投影超过 8000 字符"),
                "必须在编译期指认超长章节与上限，不能等 MySQL 1406: " + error.getMessage());
        verify(chapterMapper, never()).insertCmsTopicChapter(any());
    }

    @Test
    void descriptionProjectionAtColumnLimitRemainsAccepted()
    {
        ChapterDTO chapter = chapter("投影边界");
        chapter.setBlocks(Arrays.asList(text(repeat("甲", 3999)), text(repeat("乙", 4000))));

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        assertEquals(ChapterFlowCompiler.MAX_DESCRIPTION_LENGTH,
                insertedChapters.get(0).getDescription().length(),
                "description 恰好等于 varchar(8000) 上限时仍应完整落库");
    }

    @Test
    void schemaAndBlockGrammarAreExplicitlyValidated()
    {
        ChapterDTO wrongVersion = chapter("版本错");
        wrongVersion.setSchemaVersion(2);
        wrongVersion.setBlocks(Collections.<ChapterBlockDTO>emptyList());
        ChapterFlowValidationException versionError = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(wrongVersion), MEMBER_ID, "tester"));
        assertTrue(versionError.getMessage().contains("schemaVersion"), versionError.getMessage());

        // 2026-08-16:image / audio 已是合法块类型，未知类型的样本换成 video ——
        // 继续拿 image 当反例，这条闸会被「image 块不能携带 content」接住，
        // 看起来仍然红，实际上已经不再证明「未知类型被拒」。
        // 2026-09-06:audio 仍在白名单里（章节旁白与故事流块并存），见下面 audioStaysAValidBlockType。
        ChapterDTO wrongType = chapter("类型错");
        ChapterBlockDTO unknown = text("正文");
        unknown.setType("video");
        wrongType.setBlocks(Collections.singletonList(unknown));
        ChapterFlowValidationException typeError = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(wrongType), MEMBER_ID, "tester"));
        assertTrue(typeError.getMessage().contains("块类型"), typeError.getMessage());

        ChapterDTO textWithIndex = chapter("字段串位", node("节点"));
        ChapterBlockDTO invalidText = text("正文");
        invalidText.setNodeIndex(0);
        textWithIndex.setBlocks(Arrays.asList(invalidText, nodeBlock(0)));
        ChapterFlowValidationException textFieldError = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(textWithIndex), MEMBER_ID, "tester"));
        assertTrue(textFieldError.getMessage().contains("text 块不能携带 nodeIndex"),
                "必须由 text 字段形状闸拒绝: " + textFieldError.getMessage());

        ChapterDTO nodeWithContent = chapter("字段串位", node("节点"));
        ChapterBlockDTO invalidNode = nodeBlock(0);
        invalidNode.setContent("不该出现");
        nodeWithContent.setBlocks(Collections.singletonList(invalidNode));
        ChapterFlowValidationException nodeFieldError = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(nodeWithContent), MEMBER_ID, "tester"));
        assertTrue(nodeFieldError.getMessage().contains("node 块不能携带 content"),
                "必须由 node 字段形状闸拒绝: " + nodeFieldError.getMessage());
    }

    @Test
    void mediaAltTextJsonRoundTripSurvivesWriteAndPlayerProgressProjection() throws Exception
    {
        ChapterBlockDTO image = JSON.readValue(
                "{\"type\":\"image\",\"url\":\"media-asset:901\",\"altText\":\"路口的指示牌\"}", ChapterBlockDTO.class);
        ChapterBlockDTO audio = JSON.readValue(
                "{\"type\":\"audio\",\"url\":\"media-asset:902\",\"altText\":\"向左转后到达广场\"}", ChapterBlockDTO.class);
        assertEquals("路口的指示牌", JSON.readTree(JSON.writeValueAsString(image)).path("altText").asText());
        assertEquals("向左转后到达广场", JSON.readTree(JSON.writeValueAsString(audio)).path("altText").asText());
        ChapterDTO chapter = chapter("媒体章", node("关卡"));
        chapter.setBlocks(Arrays.asList(text("开场"), image, nodeBlock(0), audio));

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        CmsTopicChapter saved = updatedChapters.get(0);
        List<Map<String, Object>> stored = storedBlocks(saved);
        assertEquals("路口的指示牌", stored.get(1).get("altText"));
        assertEquals("向左转后到达广场", stored.get(3).get("altText"));
        assertFalse(saved.getDescription().contains("路口的指示牌"));
        List<Object> resolved = ChapterBlockReadSupport.resolve(saved, Collections.emptyList());
        List<Object> beforeNode = ChapterBlockReadSupport.projectForPlayer(resolved, Collections.emptySet());
        assertEquals(3, beforeNode.size(), "未完成节点后的音频及转写不能提前下发");
        assertEquals("路口的指示牌", ((Map<?, ?>) beforeNode.get(1)).get("altText"));
        List<Object> afterNode = ChapterBlockReadSupport.projectForPlayer(resolved,
                Collections.singleton(longValue(stored.get(2).get("nodeId"))));
        assertEquals("向左转后到达广场", ((Map<?, ?>) afterNode.get(3)).get("altText"));
    }

    @Test
    void mediaAltTextMissingNullAndEmptyKeepExistingMediaCompatible() throws Exception
    {
        ChapterBlockDTO image = JSON.readValue(
                "{\"type\":\"image\",\"url\":\"media-asset:901\"}", ChapterBlockDTO.class);
        ChapterBlockDTO audio = JSON.readValue(
                "{\"type\":\"audio\",\"url\":\"media-asset:902\",\"altText\":null}", ChapterBlockDTO.class);
        assertNull(image.getAltText());
        assertNull(audio.getAltText());
        ChapterBlockDTO empty = imageBlock("media-asset:903");
        empty.setAltText("");
        ChapterDTO chapter = chapter("旧媒体章", node("关卡"));
        chapter.setBlocks(Arrays.asList(image, audio, empty, nodeBlock(0)));

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        List<Map<String, Object>> stored = storedBlocks(updatedChapters.get(0));
        assertFalse(stored.get(0).containsKey("altText"));
        assertFalse(stored.get(1).containsKey("altText"));
        assertEquals("", stored.get(2).get("altText"));
        assertEquals("media-asset:901", stored.get(0).get("url"));
        assertEquals("media-asset:902", stored.get(1).get("url"));
    }

    @Test
    void mediaAltTextAcceptsLimitAndRejectsOversizeBeforeWrites() throws Exception
    {
        for (String type : Arrays.asList("image", "audio"))
        {
            ChapterBlockDTO media = imageBlock("media-asset:901");
            media.setType(type);
            media.setAltText(repeat("字", 2000));
            ChapterDTO chapter = chapter("媒体章", node("关卡"));
            chapter.setBlocks(Arrays.asList(media, nodeBlock(0)));
            String stored = ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter))
                    .getChapters().get(0).materializeBlocks(Collections.singletonList(901L));
            assertEquals(media.getAltText(), JSON.readTree(stored).get(0).path("altText").asText());

            media.setAltText(repeat("字", 2001));
            ChapterFlowValidationException failure = assertThrows(ChapterFlowValidationException.class,
                    () -> service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester"));
            assertTrue(failure.getMessage().contains("媒体文字替代最多 2000 字"));
        }
        verify(chapterMapper, never()).insertCmsTopicChapter(any());
        verify(nodeMapper, never()).insertCmsTopicNode(any());
    }

    /**
     * 章节图片块（2026-08-16）。写入端是严格白名单，媒体块要真能落进 blocks_json，
     * 否则编辑器摆好的图在提交时被整章打回，而报错只说「块类型只允许…」。
     */
    @Test
    void mediaBlocksAreMaterializedWithUrl() throws Exception
    {
        ChapterDTO chapter = chapter("有图章", node("关卡"));
        chapter.setBlocks(Arrays.asList(
                text("开场"), imageBlock("media-asset:901"),
                nodeBlock(0), text("锁后正文")));

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        List<Map<String, Object>> stored = storedBlocks(updatedChapters.get(0));
        assertEquals(4, stored.size(), "媒体块必须原样落库，不得被静默丢弃");
        assertEquals("image", stored.get(1).get("type"));
        assertEquals("media-asset:901", stored.get(1).get("url"));
        assertFalse(stored.get(1).containsKey("duration"), "块只承载 type + url，不该冒出别的键");
    }

    /**
     * ★ 2026-09-06 用户裁决：章节背景旁白（chapter.audioUrl，进本章自动播）与故事流里
     * **带位置的音频块**并存 —— 「章节一个音频，里面节点一个音频，不冲突」。
     * 所以 audio 必须留在块白名单里：拒掉它，作者在故事流插的那段会在提交时被整章打回。
     * （9-04 曾把 audio 撤出白名单，那一版已被本条裁决推翻。）
     */
    @Test
    void audioStaysAValidBlockType() throws Exception
    {
        ChapterDTO chapter = chapter("音频章", node("关卡"));
        chapter.setBlocks(Arrays.asList(
                text("开场"), audioBlock("media-asset:902"), nodeBlock(0)));

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        List<Map<String, Object>> stored = storedBlocks(updatedChapters.get(0));
        assertEquals("audio", stored.get(1).get("type"));
        assertEquals("media-asset:902", stored.get(1).get("url"));
        assertFalse(stored.get(1).containsKey("duration"), "块只承载 type + url");
    }

    /** 章节音频改走章节字段：DTO 上送 audioUrl，必须原样落到实体（少一个字段这列就永远是 NULL）。 */
    @Test
    void chapterAudioUrlIsCarriedOntoTheChapterEntity()
    {
        ChapterDTO chapter = chapter("旁白章", node("关卡"));
        chapter.setAudioUrl("media-asset:903");
        chapter.setBlocks(Arrays.asList(text("开场"), nodeBlock(0)));

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        assertEquals("media-asset:903", insertedChapters.get(0).getAudioUrl());
    }

    /**
     * ★ description 是章节摘要，会漏到列表页与分享卡。把媒体 url 投影进去不会报错，
     * 只会在列表页出现一行 media-asset:901 —— 前端 pro-editor-story 用的是同一条口径。
     */
    @Test
    void mediaBlocksNeitherProjectIntoDescriptionNorCloseTheProjectionWindow()
    {
        ChapterDTO chapter = chapter("投影章", node("关卡"));
        chapter.setBlocks(Arrays.asList(
                text("第一段"), imageBlock("media-asset:901"), text("第二段"), nodeBlock(0)));

        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");

        assertEquals("第一段\n第二段", insertedChapters.get(0).getDescription(),
                "媒体块既不进摘要，也不能把它后面的文字块挡在投影窗口之外");
    }

    @Test
    void mediaBlockFieldShapeIsGated()
    {
        ChapterDTO noUrl = chapter("缺地址", node("节点"));
        noUrl.setBlocks(Arrays.asList(imageBlock("   "), nodeBlock(0)));
        ChapterFlowValidationException urlError = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(noUrl), MEMBER_ID, "tester"));
        assertTrue(urlError.getMessage().contains("image 块缺少 url"),
                "必须由媒体 url 闸拒绝: " + urlError.getMessage());

        ChapterDTO withContent = chapter("串位", node("节点"));
        ChapterBlockDTO dirty = imageBlock("media-asset:901");
        dirty.setContent("不该出现");
        withContent.setBlocks(Arrays.asList(dirty, nodeBlock(0)));
        ChapterFlowValidationException contentError = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(withContent), MEMBER_ID, "tester"));
        assertTrue(contentError.getMessage().contains("image 块不能携带 content"),
                "必须由媒体字段形状闸拒绝: " + contentError.getMessage());

        ChapterDTO longUrl = chapter("地址过长", node("节点"));
        longUrl.setBlocks(Arrays.asList(
                imageBlock(repeat("u", ChapterFlowCompiler.MAX_URL_LENGTH + 1)), nodeBlock(0)));
        ChapterFlowValidationException lengthError = assertThrows(ChapterFlowValidationException.class,
                () -> service.updateTopicByOwnerV2(update(longUrl), MEMBER_ID, "tester"));
        assertTrue(lengthError.getMessage().contains("url 超过"),
                "必须由 url 长度闸拒绝: " + lengthError.getMessage());
    }

    private void set(String field, Object value)
    {
        ReflectionTestUtils.setField(service, field, value);
    }

    @Test
    void blockIdentifiersSurviveV2SaveReadAndReordering() throws Exception {
        ChapterDTO chapter = chapter("可恢复章节", node("入口"));
        ChapterBlockDTO first = text("第一段"); first.setKey("paragraph-first");
        ChapterBlockDTO point = nodeBlock(0); point.setKey("place-entry");
        ChapterBlockDTO image = imageBlock("media-asset:901");
        chapter.setBlocks(Arrays.asList(first, point, image));
        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");
        com.fasterxml.jackson.databind.JsonNode read = JSON.valueToTree(
                com.chengyinhub.business.util.ChapterBlockReadSupport.resolve(updatedChapters.get(0), insertedNodes));
        assertEquals("paragraph-first", read.get(0).path("key").asText());
        String generated = read.get(2).path("key").asText();
        assertTrue(generated.matches("[A-Za-z0-9_-]{1,64}"), "旧输入补齐标识并经保存读取返回");
        first.setKey(read.get(0).path("key").asText());
        image.setKey(generated);
        chapter.setBlocks(Arrays.asList(image, point, first));
        service.updateTopicByOwnerV2(update(chapter), MEMBER_ID, "tester");
        com.fasterxml.jackson.databind.JsonNode reread = JSON.valueToTree(
                com.chengyinhub.business.util.ChapterBlockReadSupport.resolve(updatedChapters.get(1), insertedNodes));
        assertEquals("paragraph-first", reread.get(2).path("key").asText());
        assertEquals(generated, reread.get(0).path("key").asText());
    }

    @Test
    void duplicateBlockIdentifiersCannotAmbiguouslyResume() {
        ChapterDTO chapter = chapter("重复内容标识");
        ChapterBlockDTO first = text("第一段"); first.setKey("same");
        ChapterBlockDTO second = text("第二段"); second.setKey("same");
        chapter.setBlocks(Arrays.asList(first, second));
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
        second.setKey("../unsafe");
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
    }

    @Test
    void openingIsSavedSeparatelyAndExistingStorySurvivesAnEditorThatOmitsIt() throws Exception {
        ChapterDTO opening = chapter("序章");
        opening.setOpening(true);
        opening.setBlocks(Collections.singletonList(text("还没有抵达第一站")));
        ChapterDTO first = chapter("第一站");
        first.setId(12L);
        first.setBlocks(Collections.singletonList(text("街道")));
        existing.setJourneyStoryJson("{\"schemaVersion\":1,\"stages\":[{\"chapterId\":12,\"question\":\"开始了吗\"}]}");
        service.updateTopicByOwnerV2(update(opening, first), MEMBER_ID, "tester");
        String saved = updatedTopics.stream().map(CmsTopic::getJourneyStoryJson)
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElse(null);
        assertNotNull(saved);
        com.fasterxml.jackson.databind.JsonNode story = JSON.readTree(saved);
        assertEquals(insertedChapters.get(0).getId().longValue(), story.path("openingChapterId").asLong());
        assertEquals(insertedChapters.get(1).getId().longValue(), story.path("stages").get(0).path("chapterId").asLong());
    }

    @Test
    void openingCannotBeARegularStationOrAppearAfterTheFirstChapter() {
        ChapterDTO opening = chapter("序章", node("医院建档"));
        opening.setOpening(true);
        opening.setBlocks(Collections.singletonList(nodeBlock(0)));
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(opening)));
        opening.setNodes(Collections.emptyList());
        opening.setBlocks(Collections.singletonList(text("序章")));
        ChapterDTO first = chapter("第一站");
        first.setBlocks(Collections.singletonList(text("第一站")));
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Arrays.asList(first, opening)));
        opening.setRecruitEnabled(1);
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(opening)));
    }

    @Test
    void existingOpeningSurvivesOmittedFlagAndCanBeExplicitlyRemoved() throws Exception {
        existing.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":10}");
        ChapterDTO opening = chapter("序章");
        opening.setId(10L);
        opening.setBlocks(Collections.singletonList(text("开局文字")));
        service.updateTopicByOwnerV2(update(opening), MEMBER_ID, "tester");
        assertTrue(Boolean.TRUE.equals(opening.getOpening()));
        String saved = updatedTopics.stream().map(CmsTopic::getJourneyStoryJson)
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow(AssertionError::new);
        assertEquals(insertedChapters.get(0).getId().longValue(), JSON.readTree(saved).path("openingChapterId").asLong());
        updatedTopics.clear();
        opening.setOpening(false);
        service.updateTopicByOwnerV2(update(opening), MEMBER_ID, "tester");
        saved = updatedTopics.stream().map(CmsTopic::getJourneyStoryJson)
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow(AssertionError::new);
        assertFalse(JSON.readTree(saved).has("openingChapterId"));
    }

    @Test
    void topicAddressComesFromFirstRealStationAfterTheOpening() {
        ChapterDTO opening = chapter("序章");
        opening.setOpening(true);
        opening.setBlocks(Collections.singletonList(text("开局")));
        NodeDTO destination = node("第一站");
        destination.setAddress("新街道 1 号");
        ChapterDTO first = chapter("出发", destination);
        first.setBlocks(Collections.singletonList(nodeBlock(0)));
        existing.setAddressName("旧街道");
        service.updateTopicByOwnerV2(update(opening, first), MEMBER_ID, "tester");
        assertTrue(updatedTopics.stream().anyMatch(topic -> "新街道 1 号".equals(topic.getAddressName())));
    }

    private TopicUpdateDTO update(ChapterDTO... chapters)
    {
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC_ID);
        dto.setName("测试主题");
        dto.setProductType(ProductType.ORIENTEERING);
        dto.setOpenMerchantPool(1);
        dto.setChapters(new ArrayList<>(Arrays.asList(chapters)));
        dto.setTickets(new ArrayList<com.chengyinhub.business.domain.dto.ActivityTicketRequest>());
        dto.setCollaboratorIds(new ArrayList<Long>());
        return dto;
    }

    private ChapterDTO legacyChapter()
    {
        ChapterDTO chapter = chapter("旧版章", node("旧节点"));
        chapter.setSchemaVersion(null);
        chapter.setBlocks(null);
        return chapter;
    }

    private ChapterDTO chapter(String name, NodeDTO... nodes)
    {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setName(name);
        chapter.setSchemaVersion(ChapterFlowCompiler.SCHEMA_VERSION);
        chapter.setRequired(1);
        chapter.setNodes(new ArrayList<>(Arrays.asList(nodes)));
        return chapter;
    }

    private NodeDTO node(String name)
    {
        NodeDTO node = new NodeDTO();
        node.setName(name);
        node.setNodeTime(5);
        return node;
    }

    private ChapterBlockDTO text(String content)
    {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("text");
        block.setContent(content);
        return block;
    }

    private ChapterBlockDTO imageBlock(String url)
    {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("image");
        block.setUrl(url);
        return block;
    }

    private ChapterBlockDTO audioBlock(String url)
    {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("audio");
        block.setUrl(url);
        return block;
    }

    private ChapterBlockDTO nodeBlock(int nodeIndex)
    {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("node");
        block.setNodeIndex(nodeIndex);
        return block;
    }

    private ChapterBlockDTO beat(String beat, String field, int nodeIndex, String content)
    {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("text");
        block.setBeat(beat);
        block.setField(field);
        block.setNodeIndex(Integer.valueOf(nodeIndex));
        block.setContent(content);
        return block;
    }

    private List<Map<String, Object>> storedBlocks(CmsTopicChapter chapter) throws Exception
    {
        return JSON.readValue(chapter.getBlocksJson(), new TypeReference<List<Map<String, Object>>>() { });
    }

    private Long longValue(Object value)
    {
        return value == null ? null : ((Number) value).longValue();
    }

    private String repeat(String value, int count)
    {
        return String.join("", Collections.nCopies(count, value));
    }
}
