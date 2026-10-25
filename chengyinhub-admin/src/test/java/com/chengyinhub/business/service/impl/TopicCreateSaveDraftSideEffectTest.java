package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import com.chengyinhub.business.service.support.TopicPublicationTestWiring;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 草稿不公开；正式发布先上架并保留后台审核任务。 */
class TopicCreateSaveDraftSideEffectTest {

    private CmsTopicServiceImpl svc;
    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private IAuditTaskService auditTaskService;
    private final List<CmsTopicChapter> writtenChapters = new ArrayList<>();
    private final List<CmsTopicNode> writtenNodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        svc = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        // createTopic 途经的协作者很多且与本测试无关:全量置 mock,再对断言目标取回引用。
        for (Field f : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            try {
                if (f.get(svc) == null) {
                    ReflectionTestUtils.setField(svc, f.getName(), Mockito.mock(f.getType()));
                }
            } catch (Exception ignore) {
            }
        }
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wireRelocatedCollaborators(svc, publicationField -> org.mockito.Mockito.mock(publicationField.getType()));
        topicMapper = (CmsTopicMapper) ReflectionTestUtils.getField(svc, "cmsTopicMapper");
        activityMapper = (CmsActivityMapper) ReflectionTestUtils.getField(svc, "cmsActivityMapper");
        // CORE OWN-C03-15：自办活动插入后须生成有效身份，否则 fail-closed；夹具按真实插入回填 id。
        org.mockito.Mockito.lenient().when(activityMapper.insertCmsActivity(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            ((com.chengyinhub.business.domain.CmsActivity) inv.getArgument(0)).setId(880001L);
            return 1;
        });
        auditTaskService = (IAuditTaskService) ReflectionTestUtils.getField(svc, "auditTaskService");
        doAnswer(inv -> {
            CmsTopic topic = inv.getArgument(0);
            topic.setId(700L);
            when(topicMapper.selectCmsTopicById(700L)).thenReturn(topic);
            doAnswer(update -> {
                CmsTopic patch = update.getArgument(0);
                if (patch.getStatus() != null) topic.setStatus(patch.getStatus());
                if (patch.getUserStatus() != null) topic.setUserStatus(patch.getUserStatus());
                return 1;
            }).when(topicMapper).updateCmsTopic(any());
            return 1;
        }).when(topicMapper).insertCmsTopic(any());
        ReflectionTestUtils.setField(svc, "themeStateKeyService", new com.chengyinhub.business.service.support.ThemeStateKeyService(
                Mockito.mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class)));
        wirePublicationInputs();
        svc = com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(svc);
    }

    private void wirePublicationInputs() {
        CmsTopicChapterMapper chapters = (CmsTopicChapterMapper) TopicPublicationTestWiring.get(svc, "cmsTopicChapterMapper");
        CmsTopicNodeMapper nodes = (CmsTopicNodeMapper) TopicPublicationTestWiring.get(svc, "cmsTopicNodeMapper");
        CmsMemberTemplateMapper templates = (CmsMemberTemplateMapper) TopicPublicationTestWiring.get(svc, "cmsMemberTemplateMapper");
        TopicPublicationTestWiring.set(svc, "topicStateKeyRefService", new TopicStateKeyRefService(nodes, templates, chapters));
        AtomicLong chapterIds = new AtomicLong(801L), nodeIds = new AtomicLong(901L);
        doAnswer(call -> {
            CmsTopicChapter row = call.getArgument(0); row.setId(chapterIds.getAndIncrement());
            writtenChapters.add(row); return 1;
        }).when(chapters).insertCmsTopicChapter(any());
        doAnswer(call -> {
            CmsTopicNode row = call.getArgument(0); row.setId(nodeIds.getAndIncrement());
            writtenNodes.add(row); return 1;
        }).when(nodes).insertCmsTopicNode(any());
        when(chapters.selectCmsTopicChapterList(any())).thenAnswer(call -> new ArrayList<>(writtenChapters));
        when(nodes.selectCmsTopicNodeList(any())).thenAnswer(call -> new ArrayList<>(writtenNodes));
        when(chapters.selectByTopicForUpdate(700L)).thenAnswer(call -> new ArrayList<>(writtenChapters));
        when(nodes.selectByTopicForUpdate(700L)).thenAnswer(call -> new ArrayList<>(writtenNodes));
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(publicationTransactions().getDataSource()));
            var frozen = new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) call.getArgument(1));
            assertEquals(1, frozen.path("runtimeSnapshotVersion").asInt());
            assertEquals(writtenNodes.size(), frozen.path("routeStations").size());
            assertEquals(writtenNodes.get(0).getId().longValue(), frozen.path("routeStations").get(0).path("nodeId").asLong());
            return 1;
        }).when(topicMapper).publishRouteSnapshot(any(), any(), any());
    }

    private DataSourceTransactionManager publicationTransactions() {
        return (DataSourceTransactionManager) ReflectionTestUtils.getField(svc, "transactionManager");
    }

    private TopicCreateDTO dto(Integer saveDraft) {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setName("测试城市定向");
        dto.setProductType(ProductType.ORIENTEERING);
        dto.setPublishMode("pro");
        dto.setChapters(new ArrayList<>());
        if (!Integer.valueOf(1).equals(saveDraft)) {
            NodeDTO node = new NodeDTO(); node.setName("原请求地点"); node.setAddress("人民广场"); node.setNodeTime(30);
            ChapterDTO chapter = new ChapterDTO(); chapter.setName("原请求章节");
            chapter.setNodes(new ArrayList<>(Collections.singletonList(node)));
            dto.getChapters().add(chapter);
        }
        dto.setSaveDraft(saveDraft);
        return dto;
    }

    @Test
    void draftCreateSkipsSelfHostShellAndAudit() {
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto(1), 42L, "tester");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).insertCmsTopic(cap.capture());
        assertEquals(Integer.valueOf(0), cap.getValue().getStatus(), "草稿:待审态位,但不提审");
        verify(topicMapper).updateTopicDraftState(700L, 1);
        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    /** 专业版发布即建活动，同时创建后台审核任务。 */
    @Test
    void publishedProCreateSubmitsPostReviewAndBuildsPublicShell() {
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto(null), 42L, "tester");

        verify(activityMapper).insertCmsActivity(any(CmsActivity.class));
        verify(auditTaskService).submit(anyString(), anyLong(), anyLong(), anyString(), anyString());
        verify(topicMapper, never()).updateTopicDraftState(anyLong(), any());
    }

    /** 正面对照:简易版免审直发(status=1),发布时照旧建公开活动壳、不提审。 */
    @Test
    void publishedSimpleCreateStillBuildsSelfHostShell() {
        TopicCreateDTO simple = dto(null);
        simple.setPublishMode("simple");
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(simple, 42L, "tester");

        verify(activityMapper).insertCmsActivity(any(CmsActivity.class));
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), anyString());
        verify(topicMapper, never()).updateTopicDraftState(anyLong(), any());
        // AUDIT-G2-4:免审直发要落 check_status=1(机审通过/免审),与专业版后审中的 0 区分开。
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).insertCmsTopic(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getValue().getCheckStatus(), "简易版直发 → check_status=1");
    }

    /** AUDIT-G2-4 对照:专业版发布后审中仍是 0,草稿不写 1。 */
    @Test
    void proAndDraftCreateDoNotClaimMachinePassed() {
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto(1), 42L, "tester");
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).insertCmsTopic(cap.capture());
        assertTrue(!Integer.valueOf(1).equals(cap.getValue().getCheckStatus()), "草稿不得写 check_status=1");
    }

    /**
     * release-0917 同步 master(#1078)× rule-funds,总控裁定 C:pro 建题即待审、不建壳,欠款逾期闸不能只留在建壳处 ——
     * 提交时显式拦,主理人在提交那一刻看到原因(与合并前「建题即拦」一致)。负控:删掉 createTopicInternal 里那行
     * settlementDebtLedger.requireNoOverdueDebt(memberId) → 本用例红。
     */
    @Test
    void publishedProCreate_withOverdueDebt_isRejectedAtSubmission() {
        SettlementDebtLedger ledger = (SettlementDebtLedger) ReflectionTestUtils.getField(svc, "settlementDebtLedger");
        doThrow(new ServiceException("SETTLEMENT_DEBT_OVERDUE:你有逾期未补足的欠款,暂不能发布新活动,请联系平台客服处理"))
                .when(ledger).requireNoOverdueDebt(42L);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        ServiceException e = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto(null), 42L, "tester"));

        assertTrue(e.getMessage().startsWith("SETTLEMENT_DEBT_OVERDUE"), e.getMessage());
        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));
    }

    @Test
    void publishedCreateWithoutNodesIsRejectedBeforeSnapshotOrPublicShell() {
        TopicCreateDTO request = dto(null); request.setPublishMode("simple");
        request.setChapters(new ArrayList<>());
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(request, 42L, "tester"));

        assertTrue(failure.getMessage().contains("没有可运行节点"), failure.getMessage());
        verify(topicMapper, never()).publishRouteSnapshot(any(), any(), any());
        verify(activityMapper, never()).insertCmsActivity(any());
    }

    @Test
    void snapshotWriteFailureRollsBackOriginalSpringTransactionBeforePublicShell() throws Exception {
        AtomicReference<java.sql.Connection> originalResource = new AtomicReference<>();
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(publicationTransactions().getDataSource());
            org.junit.jupiter.api.Assertions.assertNotNull(holder);
            originalResource.set(holder.getConnection());
            throw new IllegalStateException("injected snapshot write failure");
        }).when(topicMapper).publishRouteSnapshot(any(), any(), any());
        TopicCreateDTO request = dto(null); request.setPublishMode("simple");
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(request, 42L, "tester"));

        assertEquals("injected snapshot write failure", failure.getMessage());
        org.junit.jupiter.api.Assertions.assertNotNull(originalResource.get());
        verify(originalResource.get()).rollback();
        verify(originalResource.get(), never()).commit();
        verify(activityMapper, never()).insertCmsActivity(any());
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), anyString());
    }
}
