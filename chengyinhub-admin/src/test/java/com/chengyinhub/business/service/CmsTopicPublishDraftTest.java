package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.business.service.support.TopicBundleReviewService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3 发布草稿 审核绕过防护单测。
 * 核心:非草稿(rejected 审核未通过 / 已上架)不得借道 publish-draft 改状态 → clearDraftIfDraft 返 0 时不 mutate。
 */
class CmsTopicPublishDraftTest {

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


    private CmsTopicMapper cmsTopicMapper;
    private IAuditTaskService auditTaskService;
    private CmsTopicNodeMapper cmsTopicNodeMapper;
    private com.chengyinhub.business.mapper.CmsTopicChapterMapper chapterMapper;
    private final java.util.Map<Long, java.util.List<CmsTopicNode>> authoredNodes = new java.util.LinkedHashMap<>();
    private final java.util.Map<Long, com.chengyinhub.business.domain.CmsTopicChapter> authoredChapters = new java.util.LinkedHashMap<>();
    private final java.util.Map<Long, CmsMemberTemplate> authoredTemplates = new java.util.LinkedHashMap<>();
    private CmsMemberTemplateMapper cmsMemberTemplateMapper;
    private ThemeStateKeyMapper themeStateKeyMapper;
    private CmsActivityMapper cmsActivityMapper;
    private TopicBundleReviewService topicBundleReviewService;
    private ITopicCommercialReviewService topicCommercialReviewService;
    private CmsTopicServiceImpl svc;
    private com.chengyinhub.business.mapper.AppMediaAssetMapper mediaAssetMapper;

    @BeforeEach
    void setUp() {
        callerTransactionActive = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        cmsTopicMapper = mock(CmsTopicMapper.class);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(anyLong()))
                .thenAnswer(inv -> cmsTopicMapper.selectCmsTopicById(inv.getArgument(0)));
        auditTaskService = mock(IAuditTaskService.class);
        cmsTopicNodeMapper = mock(CmsTopicNodeMapper.class);
        chapterMapper = mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class);
        cmsMemberTemplateMapper = mock(CmsMemberTemplateMapper.class);
        themeStateKeyMapper = mock(ThemeStateKeyMapper.class);
        cmsActivityMapper = mock(CmsActivityMapper.class);
        CmsMemberTemplate question = new CmsMemberTemplate();
        question.setId(101L); question.setMemberId(100L); question.setStatus(1L);
        question.setValidationMethod(1); question.setTitle("原发布题");
        question.setQuestionName("原输入题"); question.setQuestionAnswer("fixture-answer");
        authoredTemplates.put(101L, question);
        when(cmsMemberTemplateMapper.selectCmsMemberTemplateById(anyLong()))
                .thenAnswer(call -> authoredTemplates.get(call.getArgument(0)));
        when(cmsMemberTemplateMapper.selectCmsMemberTemplateByIds(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call -> templateRows(call.getArgument(0)));
        when(cmsMemberTemplateMapper.selectCmsMemberTemplateByIdsForUpdate(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call -> templateRows(call.getArgument(0)));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenAnswer(call -> {
            CmsTopicNode query = call.getArgument(0);
            return query == null || query.getTopicId() == null ? Collections.emptyList() : topicNodes(query.getTopicId());
        });
        when(cmsTopicNodeMapper.selectByTopicForUpdate(anyLong())).thenAnswer(call -> topicNodes(call.getArgument(0)));
        when(chapterMapper.selectCmsTopicChapterList(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicChapter query = call.getArgument(0);
            return query == null || query.getTopicId() == null ? Collections.emptyList()
                    : Collections.singletonList(topicChapter(query.getTopicId()));
        });
        when(chapterMapper.selectByTopicForUpdate(anyLong()))
                .thenAnswer(call -> Collections.singletonList(topicChapter(call.getArgument(0))));
        when(themeStateKeyMapper.lockTopicForStateKeyWrite(anyLong())).thenAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(publicationDataSource));
            CmsTopic current = cmsTopicMapper.selectCmsTopicByIdForUpdate(call.getArgument(0));
            return current == null ? null : current.getId();
        });
        when(themeStateKeyMapper.selectByTopicIdForUpdate(anyLong())).thenReturn(Collections.emptyList());
        // CORE OWN-C03-15：自办活动插入后须生成有效身份，否则 fail-closed；夹具按真实插入回填 id。
        org.mockito.Mockito.lenient().when(cmsActivityMapper.insertCmsActivity(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            ((com.chengyinhub.business.domain.CmsActivity) inv.getArgument(0)).setId(880001L);
            return 1;
        });
        topicBundleReviewService = mock(TopicBundleReviewService.class);
        topicCommercialReviewService = mock(ITopicCommercialReviewService.class);
        svc = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(svc, "quotaService", mock(IQuotaService.class));
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        when(((com.chengyinhub.business.service.support.TopicSupplyReviewService) ReflectionTestUtils.getField(svc, "supplyReviewService")).lockTopic(anyLong()))
                .thenAnswer(call -> { CmsTopic locked = cmsTopicMapper.selectCmsTopicByIdForUpdate(call.getArgument(0));
                    if (locked == null) throw new IllegalStateException("主题不存在"); return locked; });
        ReflectionTestUtils.setField(svc, "auditTaskService", auditTaskService);
        ReflectionTestUtils.setField(svc, "topicBundleReviewService", topicBundleReviewService);
        ReflectionTestUtils.setField(svc, "topicCommercialReviewService", topicCommercialReviewService);
        com.chengyinhub.business.mapper.MerchantChapterOfferMapper offerMapper =
                mock(com.chengyinhub.business.mapper.MerchantChapterOfferMapper.class);
        when(offerMapper.selectByTopicIncludingInactiveForUpdate(anyLong()))
                .thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(svc, "merchantChapterOfferMapper", offerMapper);
        when(topicBundleReviewService.freezeForSubmission(anyLong(), anyLong())).thenReturn(
                new TopicBundleReviewService.Submission("{\"schemaVersion\":2}", Collections.singletonList(101L)));
        when(auditTaskService.submitOrRefreshTopicBundle(anyLong(), anyLong(), anyString())).thenReturn(901L);
        com.chengyinhub.business.domain.AuditTask pendingTask = new com.chengyinhub.business.domain.AuditTask();
        pendingTask.setId(901L);
        pendingTask.setStatus(com.chengyinhub.business.domain.AuditTask.STATUS_PENDING);
        when(auditTaskService.selectAuditTaskById(901L)).thenReturn(pendingTask);
        // F4 起专业版提交审核也验状态键,故三板斧齐备(无引用的老主题走不到注册表查询)。
        ReflectionTestUtils.setField(svc, "cmsTopicNodeMapper", cmsTopicNodeMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicChapterMapper", chapterMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(svc, "cmsMemberTemplateMapper", cmsMemberTemplateMapper);
        // Real publication/reference guards, with only the reviewed asset persistence mocked.
        com.chengyinhub.business.service.support.AppMediaReferenceGuard references =
                new com.chengyinhub.business.service.support.AppMediaReferenceGuard();
        mediaAssetMapper = mock(com.chengyinhub.business.mapper.AppMediaAssetMapper.class);
        ReflectionTestUtils.setField(references, "mapper", mediaAssetMapper);
        com.chengyinhub.business.service.support.AppMediaPublicationGuard mediaGuard =
                new com.chengyinhub.business.service.support.AppMediaPublicationGuard();
        ReflectionTestUtils.setField(mediaGuard, "references", references);
        ReflectionTestUtils.setField(mediaGuard, "topics", cmsTopicMapper);
        ReflectionTestUtils.setField(mediaGuard, "chapters", chapterMapper);
        ReflectionTestUtils.setField(mediaGuard, "nodes", cmsTopicNodeMapper);
        ReflectionTestUtils.setField(mediaGuard, "templates", cmsMemberTemplateMapper);
        ReflectionTestUtils.setField(svc, "mediaPublicationGuard", mediaGuard);
        when(mediaAssetMapper.select(901L)).thenReturn(java.util.Map.of(
                "id", 901L, "ownerType", "APP", "memberId", 7L, "reviewState", "APPROVED"));
        ReflectionTestUtils.setField(svc, "themeStateKeyService", new ThemeStateKeyService(themeStateKeyMapper));
        // R8 起引用收集在 TopicStateKeyRefService(节点块 + 数值规则),发布闸与试检查共用。
        TopicStateKeyRefService stateReferences =
                new TopicStateKeyRefService(cmsTopicNodeMapper, cmsMemberTemplateMapper, chapterMapper);
        ReflectionTestUtils.setField(stateReferences, "cmsTopicMapper", cmsTopicMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(svc, "topicStateKeyRefService", stateReferences);
        // R18-DRAFT:草稿期不预建自办活动壳,发布时要补建 —— 这条链路的两个协作者。
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", cmsActivityMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(svc, "omsTicketService", mock(IOmsTicketService.class));
        // R19-DRAFT2:发布闸读票种(完整度),不在场就是 NPE 假红。
        ReflectionTestUtils.setField(svc, "omsTicketMapper", mock(OmsTicketMapper.class));
        // release-0917 同步 master:rule-funds 的欠款逾期闸在 publishDraft 提交口与 generateSelfHostActivity 里,手工装配须补上;mock 默认无逾期。
        ReflectionTestUtils.setField(svc, "settlementDebtLedger",
                mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        when(cmsTopicMapper.updateCmsTopic(any(CmsTopic.class))).thenAnswer(call -> {
            CmsTopic patch = call.getArgument(0);
            CmsTopic current = cmsTopicMapper.selectCmsTopicByIdForUpdate(patch.getId());
            org.junit.jupiter.api.Assertions.assertNotNull(current, "a publication update requires its original current topic");
            if (patch.getStatus() != null) current.setStatus(patch.getStatus());
            if (patch.getUserStatus() != null) current.setUserStatus(patch.getUserStatus());
            if (patch.getCheckStatus() != null) current.setCheckStatus(patch.getCheckStatus());
            if (patch.getReason() != null) current.setReason(patch.getReason());
            return 1;
        });
        when(cmsTopicMapper.publishRouteSnapshot(anyLong(), nullable(String.class), any(java.util.Date.class))).thenReturn(1);
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc = com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(svc);
        org.springframework.jdbc.datasource.DataSourceTransactionManager transactions =
                (org.springframework.jdbc.datasource.DataSourceTransactionManager)
                com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(svc, "transactionManager");
        publicationDataSource = transactions.getDataSource();
    }


    private com.chengyinhub.business.domain.CmsTopicChapter topicChapter(Long topicId) {
        return authoredChapters.computeIfAbsent(topicId, id -> {
            com.chengyinhub.business.domain.CmsTopicChapter chapter = new com.chengyinhub.business.domain.CmsTopicChapter();
            chapter.setId(id * 100 + 2); chapter.setTopicId(id); chapter.setName("原发布章节");
            return chapter;
        });
    }

    private java.util.List<CmsTopicNode> topicNodes(Long topicId) {
        return authoredNodes.computeIfAbsent(topicId, id -> {
            CmsTopicNode node = new CmsTopicNode(); node.setId(id * 100 + 1); node.setTopicId(id);
            node.setChapterId(topicChapter(id).getId()); node.setStatus(1L); node.setName("原发布地点");
            node.setTemplateId(101L); node.setLatitude("31.23"); node.setLongitude("121.45");
            return Collections.singletonList(node);
        });
    }

    private java.util.List<CmsMemberTemplate> templateRows(java.util.List<Long> ids) {
        java.util.List<CmsMemberTemplate> rows = new java.util.ArrayList<>();
        for (Long id : ids) if (authoredTemplates.containsKey(id)) rows.add(authoredTemplates.get(id));
        return rows;
    }

    @Test
    void notDraft_returnsFalse_noStatusMutation() {
        // 非草稿(rejected/online):WHERE is_draft=1 命中 0 行
        when(cmsTopicMapper.clearDraftIfDraft(9L)).thenReturn(0);
        CmsTopic nonDraft = new CmsTopic(); nonDraft.setId(9L); nonDraft.setMemberId(7L);
        when(cmsTopicMapper.selectCmsTopicById(9L)).thenReturn(nonDraft);
        when(((com.chengyinhub.business.service.support.TopicSupplyReviewService) ReflectionTestUtils.getField(svc, "supplyReviewService")).lockTopic(9L)).thenReturn(nonDraft);
        assertFalse(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(9L), "非草稿不应发布(审核绕过防护)");
        verify(cmsTopicMapper, never()).updateCmsTopic(any());
        verify(topicBundleReviewService, never()).freezeForSubmission(anyLong(), anyLong());
    }

    @Test
    void draftSimple_publishesOnline() {
        when(cmsTopicMapper.clearDraftIfDraft(1L)).thenReturn(1);
        when(cmsTopicMapper.publishRouteSnapshot(eq(1L), any(String.class), any(java.util.Date.class))).thenReturn(1);
        CmsTopic t = new CmsTopic();
        t.setId(1L); t.setPublishMode("simple"); t.setMemberId(100L);
        // R19-DRAFT2:可发布的主题必须有名字(草稿保存时已要求非空)。
        t.setName("夜跑"); t.setImgUrl("media-asset:901");
        when(cmsTopicMapper.selectCmsTopicById(1L)).thenReturn(t);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(1L));
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).updateCmsTopic(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getValue().getStatus(), "简易版发布 → 已上架");
        assertEquals(Integer.valueOf(1), cap.getValue().getUserStatus());
        // AUDIT-G2-4:简易版免人工审核 = 机审已过,必须写 check_status=1,不能与「待审核(0)」长得一样。
        assertEquals(Integer.valueOf(1), cap.getValue().getCheckStatus(), "简易版免审直发 → check_status=1(机审通过/免审)");

        ArgumentCaptor<String> frozen = ArgumentCaptor.forClass(String.class);
        verify(cmsTopicMapper).publishRouteSnapshot(eq(1L), frozen.capture(), any(java.util.Date.class));
        assertTrue(frozen.getValue().contains("\"runtimeSnapshotVersion\":1"), "formal publication must freeze complete content");
        Object runtime = com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(svc, "topicRouteRuntimeService");
        assertTrue(runtime instanceof com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl);
        assertFalse(org.mockito.Mockito.mockingDetails(runtime).isMock(), "the compiler must execute on the real runtime owner");

        verify(auditTaskService, never()).submit(anyString(), any(), any(), anyString(), anyString());
        verify(topicCommercialReviewService, never()).submitProjectReview(anyLong(), anyLong(), any());
    }

    /** #380:草稿期机审存疑(review_flag=1)→ 发布不得写 check_status=1,改挂 post_review_flag 抽检单。 */
    @Test
    void draftSimpleWithReviewFlag_publishesAsPostReview_andQueuesSpotCheck() {
        when(cmsTopicMapper.clearDraftIfDraft(1L)).thenReturn(1);
        when(cmsTopicMapper.selectReviewFlag(1L)).thenReturn(1);
        CmsTopic t = new CmsTopic();
        t.setId(1L); t.setPublishMode("simple"); t.setMemberId(100L);
        t.setName("夜跑"); t.setImgUrl("media-asset:901");
        when(cmsTopicMapper.selectCmsTopicById(1L)).thenReturn(t);

        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(1L));

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).updateCmsTopic(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getValue().getStatus(), "先发后审:照常上架");
        assertEquals(Integer.valueOf(0), cap.getValue().getCheckStatus(), "机审存疑不得记成机审通过(1)");
        verify(auditTaskService).submit(eq(com.chengyinhub.business.domain.AuditTask.BIZ_TOPIC), eq(1L), eq(100L),
                eq("post_review_flag"), org.mockito.ArgumentMatchers.contains("draft_review_flag"));
    }

    @Test
    void draftSimpleWithReviewFlag_doesNotDuplicateOpenSpotCheck() {
        when(cmsTopicMapper.clearDraftIfDraft(1L)).thenReturn(1);
        when(cmsTopicMapper.selectReviewFlag(1L)).thenReturn(1);
        CmsTopic t = new CmsTopic();
        t.setId(1L); t.setPublishMode("simple"); t.setMemberId(100L);
        t.setName("夜跑"); t.setImgUrl("media-asset:901");
        when(cmsTopicMapper.selectCmsTopicById(1L)).thenReturn(t);
        com.chengyinhub.business.domain.AuditTask open = new com.chengyinhub.business.domain.AuditTask();
        open.setStatus(com.chengyinhub.business.domain.AuditTask.STATUS_PENDING);
        open.setSourceType("post_review_flag");
        when(auditTaskService.selectAuditTaskList(any())).thenReturn(Collections.singletonList(open));

        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(1L));

        verify(auditTaskService, never()).submit(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void draftFreeExploreWithRecruitDeadline_submitsProjectReview() {
        when(cmsTopicMapper.clearDraftIfDraft(8L)).thenReturn(1);
        when(cmsTopicMapper.publishRouteSnapshot(eq(8L), any(String.class), any(java.util.Date.class))).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setId(8L);
        topic.setMemberId(100L);
        topic.setName("章节招商草稿");
        topic.setPublishMode("simple");
        topic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() + 86_400_000L));
        when(cmsTopicMapper.selectCmsTopicById(8L)).thenReturn(topic);

        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(8L));

        verify(topicCommercialReviewService).submitProjectReview(
                8L, 100L, com.chengyinhub.business.domain.enums.TopicLifecycleState.RECRUITING);
    }

    @Test
    void draftAiSimple_publishesOnline() {
        when(cmsTopicMapper.clearDraftIfDraft(3L)).thenReturn(1);
        when(cmsTopicMapper.publishRouteSnapshot(eq(3L), any(String.class), any(java.util.Date.class))).thenReturn(1);
        CmsTopic t = new CmsTopic();
        t.setId(3L); t.setPublishMode("ai_simple"); t.setMemberId(100L);
        // R19-DRAFT2:简单 AI 发布要有名字 + 至少一个已确认地点。
        t.setName("夜跑");
        when(cmsTopicMapper.selectCmsTopicById(3L)).thenReturn(t);
        CmsTopicNode aiNode = new CmsTopicNode();
        aiNode.setId(31L); aiNode.setTopicId(3L); aiNode.setStatus(1L);
        aiNode.setLatitude("31.23"); aiNode.setLongitude("121.45");
        aiNode.setChapterId(topicChapter(3L).getId());
        authoredNodes.put(3L, Collections.singletonList(aiNode));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(aiNode));

        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(3L));

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).updateCmsTopic(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getValue().getStatus(), "简单 AI 草稿发布 → 已上架");
        assertEquals(Integer.valueOf(1), cap.getValue().getUserStatus());
        assertEquals(Integer.valueOf(1), cap.getValue().getCheckStatus(), "ai_simple 免审直发 → check_status=1");
        verify(auditTaskService, never()).submit(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void draftPro_publishesWhileAuditRemainsPending() {
        when(cmsTopicMapper.clearDraftIfDraft(2L)).thenReturn(1);
        CmsTopic t = new CmsTopic();
        t.setId(2L); t.setPublishMode("pro"); t.setMemberId(100L); t.setName("夜跑");
        when(cmsTopicMapper.selectCmsTopicById(2L)).thenReturn(t);
        com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO result =
                com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraftWithReview(2L);
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).updateCmsTopic(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getValue().getStatus(), "专业版发布 → 上架待后审");
        assertEquals(Integer.valueOf(0), cap.getValue().getCheckStatus());
        assertTrue(result.isPublished());
        assertEquals("PENDING", result.getReviewState());
        verify(auditTaskService).submitOrRefreshTopicBundle(eq(2L), eq(100L), anyString());
        verify(auditTaskService, never()).submit(eq("TOPIC"), eq(2L), eq(100L), anyString(), anyString());
        verify(cmsTopicMapper).publishRouteSnapshot(
                eq(2L), nullable(String.class), any(java.util.Date.class));
    }

    // ---------- AUDIT-G2-1:内容安全下架留置中的主题,发布/重发都不得直接回到在架 ----------

    @Test
    void heldDraftSimple_staysOfflineAndRequestsHumanReview() {
        when(cmsTopicMapper.clearDraftIfDraft(1L)).thenReturn(1);
        when(cmsTopicMapper.publishRouteSnapshot(eq(1L), any(String.class), any(java.util.Date.class))).thenReturn(1);
        CmsTopic t = new CmsTopic();
        t.setId(1L); t.setPublishMode("simple"); t.setMemberId(100L);
        t.setName("夜跑"); t.setImgUrl("media-asset:901");
        t.setStatus(0); t.setUserStatus(0); t.setCheckStatus(2);
        t.setReason(com.chengyinhub.business.util.TopicContentSafetyHold.REASON);
        when(cmsTopicMapper.selectCmsTopicById(1L)).thenReturn(t);

        com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(1L);

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper, org.mockito.Mockito.atLeast(0)).updateCmsTopic(cap.capture());
        for (CmsTopic patch : cap.getAllValues()) {
            assertTrue(!Integer.valueOf(1).equals(patch.getUserStatus()), "留置中不得上架");
            assertTrue(patch.getCheckStatus() == null || patch.getCheckStatus() == 2, "留置只能由人工复核清掉");
        }
        verify(auditTaskService).submit(eq("TOPIC"), eq(1L), eq(100L),
                eq(com.chengyinhub.business.util.TopicContentSafetyHold.SOURCE_RELIST), anyString());
    }

    @Test
    void heldDraftPro_keepsBundleReviewButDoesNotGoOnline() {
        when(cmsTopicMapper.clearDraftIfDraft(2L)).thenReturn(1);
        CmsTopic t = new CmsTopic();
        t.setId(2L); t.setPublishMode("pro"); t.setMemberId(100L); t.setName("夜跑");
        t.setStatus(1); t.setUserStatus(0); t.setCheckStatus(2);
        when(cmsTopicMapper.selectCmsTopicById(2L)).thenReturn(t);
        com.chengyinhub.business.domain.AuditTask open = new com.chengyinhub.business.domain.AuditTask();
        open.setId(901L); open.setStatus(com.chengyinhub.business.domain.AuditTask.STATUS_PENDING);
        when(auditTaskService.selectAuditTaskList(any())).thenReturn(Collections.singletonList(open));

        com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO result = com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraftWithReview(2L);

        verify(auditTaskService).submitOrRefreshTopicBundle(eq(2L), eq(100L), anyString());
        verify(cmsTopicMapper, never()).updateCmsTopic(any());
        assertTrue(!result.isPublished(), "留置中:组合审核照提,但不报「已发布」");
    }

    @Test
    void directPublicationStillEnforcesOnlineQuota() {
        when(cmsTopicMapper.clearDraftIfDraft(2L)).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setId(2L); topic.setMemberId(100L); topic.setName("夜跑"); topic.setPublishMode("pro");
        when(cmsTopicMapper.selectCmsTopicById(2L)).thenReturn(topic);
        IQuotaService quota = (IQuotaService) com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(svc, "quotaService");
        when(quota.lockAndCheckThemeOnline(100L, 2L)).thenReturn("在架额度已满");
        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraftWithReview(2L));
        verify(cmsTopicMapper, never()).updateCmsTopic(any());
        verify(cmsTopicMapper, never()).publishRouteSnapshot(anyLong(), nullable(String.class), any());
    }

    @Test
    void refreshedEscalatedReviewReturnsItsPersistedState() {
        when(cmsTopicMapper.clearDraftIfDraft(2L)).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setId(2L); topic.setPublishMode("pro"); topic.setMemberId(100L); topic.setName("夜跑");
        when(cmsTopicMapper.selectCmsTopicById(2L)).thenReturn(topic);
        com.chengyinhub.business.domain.AuditTask task = new com.chengyinhub.business.domain.AuditTask();
        task.setId(901L);
        task.setStatus(com.chengyinhub.business.domain.AuditTask.STATUS_ESCALATED);
        when(auditTaskService.selectAuditTaskById(901L)).thenReturn(task);

        com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO result =
                com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraftWithReview(2L);

        assertEquals("ESCALATED", result.getReviewState());
    }


    /** F4:专业版提交审核时引用坏键 → 当场失败,不进审核、不写状态,别等过审才发现。 */
    @Test
    void draftPro_blocksUnregisteredStateKeyBeforeReview() {
        when(cmsTopicMapper.clearDraftIfDraft(4L)).thenReturn(1);
        CmsTopic t = new CmsTopic();
        t.setId(4L); t.setPublishMode("pro"); t.setMemberId(100L); t.setName("夜跑");
        when(cmsTopicMapper.selectCmsTopicById(4L)).thenReturn(t);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(1L); node.setTopicId(4L); node.setName("书房"); node.setTemplateId(101L); node.setStatus(1L);
        node.setChapterId(topicChapter(4L).getId());
        authoredNodes.put(4L, Collections.singletonList(node));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node));
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(101L); template.setMemberId(100L); template.setStatus(1L);
        authoredTemplates.put(101L, template);
        template.setAdvancedConfigJson("{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"EQ\",\"value\":1}]}");
        when(cmsMemberTemplateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template);
        when(themeStateKeyMapper.selectByTopicId(4L)).thenReturn(Collections.<ThemeStateKey>emptyList());

        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(4L));

        assertTrue(error.getMessage().contains("clue.ledger_page"), error.getMessage());
        verify(cmsTopicMapper, never()).updateCmsTopic(any(CmsTopic.class));
        verify(auditTaskService, never()).submit(anyString(), any(), any(), anyString(), anyString());
    }

    /**
     * R18-DRAFT:草稿期不再预建自办活动壳(壳是公开可下单的),发布时必须补建 ——
     * 缺了这一步,「草稿 → 发布」的自办主题发布后没有活动壳,玩家无票可买。
     *
     * <p>补建只发生在主题确已上架(status=1)时；简易版直发与专业版先发布、后台后审均适用。
     * 这里第二次读(重读本事务刚写的状态)= 已上架。
     */
    @Test
    void draftPublish_buildsSelfHostActivityShellWhenMissing() {
        when(cmsTopicMapper.clearDraftIfDraft(5L)).thenReturn(1);
        when(cmsTopicMapper.publishRouteSnapshot(eq(5L), any(String.class), any(java.util.Date.class))).thenReturn(1);
        CmsTopic t = new CmsTopic();
        t.setId(5L); t.setPublishMode("simple"); t.setMemberId(100L); t.setCreateBy("publisher");
        t.setName("夜跑");
        // Current reads keep the original row; updateCmsTopic applies its actual publication status patch.
        when(cmsTopicMapper.selectCmsTopicById(5L)).thenReturn(t);
        when(cmsActivityMapper.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.<CmsActivity>emptyList());

        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(5L));

        verify(cmsActivityMapper).insertCmsActivity(any(CmsActivity.class));
    }

    /** 负控:进商家池的主题不由发布者自办,发布时不许建自办壳(壳由中标流程生成)。 */
    @Test
    void draftPublish_doesNotBuildShellForMerchantPoolTopic() {
        when(cmsTopicMapper.clearDraftIfDraft(6L)).thenReturn(1);
        CmsTopic t = new CmsTopic();
        t.setId(6L); t.setPublishMode("pro"); t.setMemberId(100L); t.setName("夜跑");
        t.setMerchantStatus(1);
        when(cmsTopicMapper.selectCmsTopicById(6L)).thenReturn(t);

        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(6L));

        verify(cmsActivityMapper, never()).insertCmsActivity(any(CmsActivity.class));
    }

    /** 壳已存在(老流程建的草稿)不重复建:发布是幂等补建,不是无脑插一条。 */
    @Test
    void draftPublish_doesNotDuplicateExistingShell() {
        when(cmsTopicMapper.clearDraftIfDraft(7L)).thenReturn(1);
        CmsTopic t = new CmsTopic();
        t.setId(7L); t.setPublishMode("pro"); t.setMemberId(100L); t.setName("夜跑");
        when(cmsTopicMapper.selectCmsTopicById(7L)).thenReturn(t);
        CmsActivity existing = new CmsActivity();
        existing.setId(9001L);
        when(cmsActivityMapper.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.singletonList(existing));

        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(7L));

        verify(cmsActivityMapper, never()).insertCmsActivity(any(CmsActivity.class));
    }
    @Test
    void changedOwnerAfterControllerReadRejectsEvenRealDraftFallbackBeforeMutation() {
        CmsTopic before = new CmsTopic(); before.setId(91L); before.setMemberId(7L); before.setImgUrl("media-asset:901");
        when(cmsTopicMapper.selectCmsTopicById(91L)).thenReturn(before);
        com.chengyinhub.business.service.support.TopicSupplyReviewService gate =
                (com.chengyinhub.business.service.support.TopicSupplyReviewService) ReflectionTestUtils.getField(svc, "supplyReviewService");
        when(gate.canResubmit(before)).thenReturn(true);
        org.junit.jupiter.api.Assertions.assertTrue(com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).canResubmitSupplyReview(91L, 7L));
        CmsTopic locked = new CmsTopic(); locked.setId(91L); locked.setMemberId(8L); locked.setImgUrl("media-asset:901");
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(91L)).thenReturn(locked);
        when(cmsTopicMapper.clearDraftIfDraft(91L)).thenReturn(1);
        com.chengyinhub.common.exception.ServiceException error = org.junit.jupiter.api.Assertions.assertThrows(
                com.chengyinhub.common.exception.ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraftWithReview(91L, 7L));
        org.junit.jupiter.api.Assertions.assertEquals(403, error.getCode());
        org.mockito.Mockito.verify(cmsTopicMapper, org.mockito.Mockito.never()).clearDraftIfDraft(91L);
        org.mockito.Mockito.verify(topicBundleReviewService, org.mockito.Mockito.never()).freezeForSubmission(91L, 8L);
        verify(mediaAssetMapper, never()).select(anyLong());
    }

    @Test
    void noActorOverloadCannotExecuteSupplyReplacementAndExplicitActorMustBePositive() {
        CmsTopic locked = new CmsTopic(); locked.setId(91L); locked.setMemberId(7L);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(91L)).thenReturn(locked);
        when(cmsTopicMapper.selectCmsTopicById(91L)).thenReturn(locked);
        com.chengyinhub.business.service.support.TopicSupplyReviewService gate =
                (com.chengyinhub.business.service.support.TopicSupplyReviewService) ReflectionTestUtils.getField(svc, "supplyReviewService");
        when(gate.canResubmit(locked)).thenReturn(true);
        when(cmsTopicMapper.clearDraftIfDraft(91L)).thenReturn(0);
        org.junit.jupiter.api.Assertions.assertNull(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraftWithReview(91L));
        org.mockito.Mockito.verify(gate, org.mockito.Mockito.never()).canResubmit(locked);
        com.chengyinhub.common.exception.ServiceException error = org.junit.jupiter.api.Assertions.assertThrows(
                com.chengyinhub.common.exception.ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraftWithReview(91L, null));
        org.junit.jupiter.api.Assertions.assertEquals(403, error.getCode());
        org.mockito.Mockito.verify(topicBundleReviewService, org.mockito.Mockito.never()).freezeForSubmission(91L, 7L);
    }

    @Test
    void heldMediaCannotReachDraftCasEvenForCorrectOwner() {
        CmsTopic topic = new CmsTopic(); topic.setId(92L); topic.setMemberId(7L);
        topic.setName("待审媒体草稿"); topic.setPublishMode("simple"); topic.setImgUrl("media-asset:902");
        when(cmsTopicMapper.selectCmsTopicById(92L)).thenReturn(topic);
        when(mediaAssetMapper.select(902L)).thenReturn(java.util.Map.of(
                "id", 902L, "ownerType", "APP", "memberId", 7L, "reviewState", "HOLD"));
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraftWithReview(92L, 7L));
        assertEquals(422, error.getCode());
        verify(mediaAssetMapper).select(902L);
        verify(cmsTopicMapper, never()).clearDraftIfDraft(92L);
        verify(topicBundleReviewService, never()).freezeForSubmission(92L, 7L);
    }

}
