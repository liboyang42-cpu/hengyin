package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.GameplayTemplateRules;
import com.chengyinhub.business.service.support.GameplayOutcomeEngine;
import com.chengyinhub.business.service.support.GameplaySurvivalEngine;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B1:发布期状态键闸 —— 引用未注册键必须发布失败,老主题行为不变。 */
class CmsTopicPublishStateKeyGuardTest {

    private static final Long TOPIC_ID = 10L;

    private CmsTopicMapper topicMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private CmsTopicChapterMapper chapterMapper;
    private ThemeStateKeyMapper keyMapper;
    private CmsTopicServiceImpl service;
    // Unit persistence/current-read fixture only; no MySQL/Spring rollback claim.
    private final java.util.List<ThemeStateKey> currentDeclarations = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        when(topicMapper.selectCmsTopicByIdForUpdate(anyLong()))
                .thenAnswer(inv -> topicMapper.selectCmsTopicById(inv.getArgument(0)));
        nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        // A publication freezes actual runnable content even when this case has no template references.
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode(1L, "原路线地点", null)));
        keyMapper = mock(ThemeStateKeyMapper.class);
        when(keyMapper.lockTopicForStateKeyWrite(TOPIC_ID)).thenAnswer(call -> {
            CmsTopic parent = topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID);
            return parent == null ? null : parent.getId();
        });
        when(keyMapper.selectByTopicIdForUpdate(TOPIC_ID)).thenAnswer(call -> new java.util.ArrayList<>(currentDeclarations));
        org.mockito.Mockito.doAnswer(call -> {
            ThemeStateKey row = call.getArgument(0);
            currentDeclarations.removeIf(old -> old.getStateKey().equals(row.getStateKey()));
            currentDeclarations.add(row); return 1;
        }).when(keyMapper).upsertThemeStateKey(any(ThemeStateKey.class));
        when(chapterMapper.selectByTopicForUpdate(TOPIC_ID)).thenAnswer(call -> {
            CmsTopicChapter query = new CmsTopicChapter(); query.setTopicId(TOPIC_ID);
            return chapterMapper.selectCmsTopicChapterList(query);
        });
        when(nodeMapper.selectByTopicForUpdate(TOPIC_ID)).thenAnswer(call -> {
            CmsTopicNode query = new CmsTopicNode(); query.setTopicId(TOPIC_ID);
            return nodeMapper.selectCmsTopicNodeList(query);
        });
        when(templateMapper.selectCmsMemberTemplateByIdsForUpdate(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            java.util.List<Long> ids = call.getArgument(0);
            java.util.List<CmsMemberTemplate> rows = new java.util.ArrayList<>();
            for (Long id : ids) {
                CmsMemberTemplate row = templateMapper.selectCmsMemberTemplateById(id);
                if (row != null) rows.add(row);
            }
            return rows;
        });
        service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "settlementDebtLedger",
                mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "cmsMemberTemplateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "themeStateKeyService", new ThemeStateKeyService(keyMapper));
        // R8 起引用收集在 TopicStateKeyRefService:节点块 + 数值规则 + 章节故事流(与发布前试检查共用同一份)。
        TopicStateKeyRefService references = new TopicStateKeyRefService(nodeMapper, templateMapper, chapterMapper);
        ReflectionTestUtils.setField(references, "cmsTopicMapper", topicMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "topicStateKeyRefService", references);
        // R18-DRAFT:发布时补建自办活动壳 —— 成功路径会走到这两个协作者,不在场就是 NPE 假红。
        ReflectionTestUtils.setField(service, "cmsActivityMapper",
                mock(com.chengyinhub.business.mapper.CmsActivityMapper.class));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "omsTicketService",
                mock(com.chengyinhub.business.service.IOmsTicketService.class));
        // R19-DRAFT2:发布闸读票种(完整度),不在场就是 NPE 假红。
        ReflectionTestUtils.setField(service, "omsTicketMapper",
                mock(com.chengyinhub.business.mapper.OmsTicketMapper.class));
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        when(topicMapper.publishRouteSnapshot(eq(TOPIC_ID), nullable(String.class), any(Date.class))).thenReturn(1);
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        service = com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service);
    }

    private java.util.List<ThemeStateKey> currentRows(java.util.List<ThemeStateKey> rows) {
        currentDeclarations.clear(); currentDeclarations.addAll(rows); return rows;
    }

    @Test
    void unregisteredKeyBlocksSimplePublishWithKeyAndNode() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode(1L, "书房", 101L)));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template(
                "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"EQ\",\"value\":1}],"
                        + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.coins\",\"value\":2}]}"));
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.singletonList(registered("clue.ledger_page"))));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("counter.coins"), error.getMessage());
        assertTrue(error.getMessage().contains("书房"), error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    @Test
    void allReferencedKeysRegisteredPublishes() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode(1L, "书房", 101L)));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template(
                "{\"check\":{\"enabled\":true,\"checkId\":\"ask_lin\",\"tier\":\"easy\","
                        + "\"mods\":[{\"when\":{\"var\":\"counter.coins\",\"op\":\"GTE\",\"value\":1},\"value\":2}],"
                        + "\"failCostTag\":\"tag.hurried\"}}"));
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Arrays.asList(
                registered("counter.coins"), registered("tag.hurried"))));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        ArgumentCaptor<CmsTopic> captured = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(captured.capture());
        assertEquals(Integer.valueOf(1), captured.getValue().getStatus(), "简易版发布 → 已上架");
    }

    @Test
    void optionTagIsAutoRegisteredOnPublish_butCounterStillBlocks() {
        // 2026-09-24:手机端「记住这个选择」贴的 tag.* 发布时自动注册,不再挡在发布闸外
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode(1L, "教室", 101L)));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template(
                "{\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"options\":[{\"id\":\"A\",\"label\":\"害虫\","
                        + "\"effects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.m_pest\"}]}]}}"));
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()),
                Collections.singletonList(registered("tag.m_pest")));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));
        ArgumentCaptor<ThemeStateKey> upserted = ArgumentCaptor.forClass(ThemeStateKey.class);
        verify(keyMapper).upsertThemeStateKey(upserted.capture());
        assertEquals("tag.m_pest", upserted.getValue().getStateKey());
        assertEquals(ThemeStateKey.TYPE_TAG, upserted.getValue().getKeyType());
    }

    @Test
    void legacyTopicWithoutAnyReferenceDoesNotTouchRegistry() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode(1L, "老节点", 101L)));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true}}"));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(keyMapper, never()).selectByTopicId(anyLong());
    }

    // ---------- R2-B10 主题数值规则:力竭标记同闸 ----------

    /** journeyRules 启用时 recovery.exhaustTag 未注册 ⇒ 发布失败,且报出是主题数值规则要的键。 */
    @Test
    void journeyRulesExhaustTagMustBeRegistered() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        CmsTopic topic = simpleDraft();
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"recovery\":{\"exhaustTag\":\"tag.injured\"}}");
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.<CmsTopicNode>emptyList());
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("tag.injured"), error.getMessage());
        assertTrue(error.getMessage().contains("主题数值规则"), "要说明这个键是主题级规则要的,不是节点引用:" + error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    @Test
    void journeyRulesExhaustTagRegisteredPublishes() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        CmsTopic topic = simpleDraft();
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"recovery\":{\"exhaustTag\":\"tag.injured\"}}");
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.singletonList(registered("tag.injured"))));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(topicMapper).publishRouteSnapshot(eq(TOPIC_ID), nullable(String.class), any(Date.class));
    }

    /**
     * R22-SEC S15:checkEnabled=false 只关检定,不豁免力竭标记的注册闸 ——
     * 该 tag 运行期(力竭休整)仍会被写进本局,未注册就不能放行发布。
     *
     * <p>负控:collectJourneyRules 改回 checkEnabled=false 提前 return,本用例立刻红(发布会通过)。
     */
    @Test
    void disabledJourneyRulesStillRequireExhaustTagRegistration() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        CmsTopic topic = simpleDraft();
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":false,"
                + "\"recovery\":{\"exhaustTag\":\"tag.injured\"}}");
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("tag.injured"), error.getMessage());
        assertTrue(error.getMessage().contains("主题数值规则"), "要说明这个键是主题级规则要的:" + error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }

    /** 存量坏配置不许被当成「没有引用」而放行(fail-closed)。 */
    @Test
    void unparsableJourneyRulesFailClosed() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        CmsTopic topic = simpleDraft();
        topic.setJourneyRulesJson("{broken");
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("旅程数值规则"), error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void approvedTopicSnapshotBlocksUnregisteredKey() {
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(approvedTopic());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode(1L, "书房", 101L)));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template(
                "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"EQ\",\"value\":1}]}"));
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        ServiceException error = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishApprovedRouteSnapshot(TOPIC_ID));

        assertTrue(error.getMessage().contains("clue.ledger_page"), error.getMessage());
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    @Test
    void approvedTopicSnapshotPassesWhenRegistered() {
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(approvedTopic());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode(1L, "书房", 101L)));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template(
                "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"EQ\",\"value\":1}]}"));
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.singletonList(registered("clue.ledger_page"))));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishApprovedRouteSnapshot(TOPIC_ID);

        verify(topicMapper).publishRouteSnapshot(eq(TOPIC_ID), nullable(String.class), any(Date.class));
    }

    /**
     * R19:叙事搬进章节故事流后,beat 块 when 引用的未注册键必须拦住发布 ——
     * 模板里的 outcome.facts / revisit.changes / brief.carry 已是死配置,
     * 只对故事流这份负责才算真闸。
     *
     * <p>负控:删掉 assertStateKeysRegistered 里 chapterKeys 的 missing 循环,本用例立刻红。
     */
    @Test
    void chapterStoryFlowBeatWhenUnregisteredKeyBlocksPublish() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.<CmsTopicNode>emptyList());
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter(
                        "[{\"type\":\"text\",\"beat\":\"brief\",\"field\":\"carry\",\"nodeId\":501,"
                                + "\"content\":\"带上那一页\","
                                + "\"when\":{\"var\":\"clue.receipt_071\",\"op\":\"GTE\",\"value\":1}}]")));
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("clue.receipt_071"), error.getMessage());
        assertTrue(error.getMessage().contains("章节故事流"),
                "要说明这个键是章节故事流要的,不是节点块引用:" + error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    @Test
    void chapterStoryFlowBeatWhenRegisteredPublishes() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        CmsTopicNode storyNode = topicNode(501L, "章节叙事地点", null); storyNode.setChapterId(88L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(storyNode));
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter(
                        "[{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"fact\",\"nodeId\":501,"
                                + "\"content\":\"翻到过\","
                                + "\"when\":{\"var\":\"clue.receipt_071\",\"op\":\"GTE\",\"value\":1}}]")));
        when(keyMapper.selectByTopicId(TOPIC_ID))
                .thenReturn(currentRows(Collections.singletonList(registered("clue.receipt_071"))));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(topicMapper).publishRouteSnapshot(eq(TOPIC_ID), nullable(String.class), any(Date.class));
    }

    private static CmsTopicChapter chapter(String blocksJson) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(88L);
        chapter.setTopicId(TOPIC_ID);
        chapter.setBlocksJson(blocksJson);
        return chapter;
    }


    @Test
    void declaredThoughtDonePublishesWithoutRegisteringRuntimeTag() {
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        CmsTopic topic = approvedTopic();
        topic.setJourneyRulesJson(thoughtRules("window"));
        topic.setJourneyStoryJson(thoughtEnding("thought.window.done"));
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishApprovedRouteSnapshot(TOPIC_ID);

        verify(topicMapper).publishRouteSnapshot(eq(TOPIC_ID), nullable(String.class), any(Date.class));
        verify(keyMapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    @Test
    void undeclaredThoughtDoneStillBlocksPublish() {
        assertThoughtPublishBlocked(thoughtRules("window"), "thought.other.done", "thought.other.done");
    }

    @Test
    void thoughtDeclaredByAnotherTopicDoesNotAuthorizeThisTopic() {
        assertThoughtPublishBlocked(thoughtRules("another"), "thought.window.done", "thought.window.done");
    }

    @Test
    void malformedThoughtRulesCannotAuthorizeRuntimeTag() {
        assertThoughtPublishBlocked("{\"schemaVersion\":1,\"thoughts\":[{\"key\":\"window\",\"need\":1}]}",
                "thought.window.done", "旅程数值规则");
    }

    @Test
    void declaredThoughtDoesNotAuthorizeUnregisteredOrdinaryKey() {
        CmsTopic topic = approvedTopic();
        topic.setJourneyRulesJson(thoughtRules("window"));
        topic.setJourneyStoryJson(thoughtEnding("thought.window.done").replace(
                "\"op\":\"HAS_TAG\",\"value\":\"thought.window.done\"}",
                "\"op\":\"HAS_TAG\",\"value\":\"thought.window.done\"},"
                        + "{\"op\":\"GTE\",\"var\":\"counter.missing\",\"value\":1}"));
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        ServiceException error = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishApprovedRouteSnapshot(TOPIC_ID));
        assertTrue(error.getMessage().contains("counter.missing"), error.getMessage());
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
        verify(keyMapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }


    @Test
    void declaredThoughtCannotBeWrittenByAdvancedNodeEffects() {
        for (String effect : Arrays.asList(
                "{\"op\":\"ADD_TAG\",\"value\":\"thought.window.done\"}",
                "{\"op\":\"SET\",\"var\":\"thought.window.done\",\"value\":1}",
                "{\"op\":\"INC\",\"var\":\"thought.window.done\",\"value\":1}")) {
            CmsTopic topic = approvedTopic();
            topic.setJourneyRulesJson(thoughtRules("window"));
            topic.setJourneyStoryJson(thoughtEnding("thought.window.done"));
            when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
            when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                    .thenReturn(Collections.singletonList(topicNode(1L, "书房", 101L)));
            when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template("{\"effects\":[" + effect + "]}"));
            when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

            ServiceException error = assertThrows(ServiceException.class,
                    () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishApprovedRouteSnapshot(TOPIC_ID));
            assertTrue(error.getMessage().contains("thought.window.done") && error.getMessage().contains("节点"), error.getMessage());
            verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
            verify(keyMapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
        }
    }


    @Test
    void declaredThoughtDoneCanBeReadByChapterConditions() {
        CmsTopic topic = approvedTopic();
        topic.setJourneyRulesJson(thoughtRules("window"));
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(88L);
        chapter.setTopicId(TOPIC_ID);
        chapter.setBlocksJson("[{\"type\":\"text\",\"beat\":\"outcome\",\"content\":\"窗已打开\","
                + "\"when\":{\"op\":\"HAS_TAG\",\"value\":\"thought.window.done\"}}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenReturn(Collections.singletonList(chapter));
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishApprovedRouteSnapshot(TOPIC_ID);
        verify(topicMapper).publishRouteSnapshot(eq(TOPIC_ID), nullable(String.class), any(Date.class));
        verify(keyMapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    @Test
    void undeclaredThoughtDoneInChapterConditionStillBlocksPublish() {
        CmsTopic topic = approvedTopic();
        topic.setJourneyRulesJson(thoughtRules("window"));
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(88L);
        chapter.setTopicId(TOPIC_ID);
        chapter.setBlocksJson("[{\"type\":\"text\",\"beat\":\"outcome\",\"when\":{\"op\":\"HAS_TAG\","
                + "\"value\":\"thought.other.done\"}}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenReturn(Collections.singletonList(chapter));
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishApprovedRouteSnapshot(TOPIC_ID));
        assertTrue(error.getMessage().contains("thought.other.done") && error.getMessage().contains("章节故事流"));
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }


    @Test
    void legacyChapterCannotTreatDeclaredThoughtTagAsANumericVariable() {
        CmsTopic topic = approvedTopic();
        topic.setJourneyRulesJson(thoughtRules("window"));
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(88L);
        chapter.setTopicId(TOPIC_ID);
        chapter.setBlocksJson("[{\"type\":\"text\",\"beat\":\"outcome\",\"when\":{\"op\":\"EQ\","
                + "\"var\":\"thought.window.done\",\"value\":1}}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenReturn(Collections.singletonList(chapter));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishApprovedRouteSnapshot(TOPIC_ID));
        assertTrue(error.getMessage().contains("HAS_TAG"), error.getMessage());
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    private void assertThoughtPublishBlocked(String rules, String tag, String message) {
        CmsTopic topic = approvedTopic();
        topic.setJourneyRulesJson(rules);
        topic.setJourneyStoryJson(thoughtEnding(tag));
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        ServiceException error = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishApprovedRouteSnapshot(TOPIC_ID));
        assertTrue(error.getMessage().contains(message), error.getMessage());
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
        verify(keyMapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    private static String thoughtRules(String key) {
        return "{\"schemaVersion\":1,\"thoughts\":[{\"key\":\"" + key + "\",\"name\":\"那扇窗\",\"need\":1}]}";
    }

    private static String thoughtEnding(String tag) {
        return "{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"awake\",\"title\":\"醒来\",\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"" + tag + "\"}]},"
                + "{\"code\":\"fallback\",\"title\":\"继续\",\"fallback\":true}]}}";
    }

    @Test
    void genericHpAndLuckChapterCostsAndItemsPublishWithoutSkillChecks() {
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "topicRouteRuntimeService", runtime);
        when(runtime.freezePublication(any(CmsTopic.class), nullable(String.class))).thenReturn("{}");
        when(runtime.freezePublication(any(CmsTopic.class), nullable(String.class), org.mockito.ArgumentMatchers.anyList())).thenReturn("{}");
        CmsTopic topic = approvedTopic();
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"checkEnabled\":false,"
                + "\"hp\":{\"enabled\":true,\"init\":8,\"max\":12},\"luck\":{\"enabled\":true,\"init\":4,\"max\":5}}");
        String legacy = "{\"schemaVersion\":1,\"capacity\":8,\"items\":["
                + "{\"id\":\"food\",\"name\":\"口粮\",\"image\":\"https://www.chengyinhub.com/food.png\",\"kind\":\"ITEM\",\"stat\":\"HP\",\"delta\":2},"
                + "{\"id\":\"luck\",\"name\":\"好运\",\"image\":\"https://www.chengyinhub.com/luck.png\",\"kind\":\"ITEM\",\"stat\":\"LUCK\",\"delta\":1}],"
                + "\"chapters\":[{\"index\":0,\"hpCost\":1,\"luckCost\":1}]}";
        topic.setThemeRuntimeConfigJson(legacy.substring(0, legacy.length() - 1)
                + ",\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\",\"acts\":[{\"actKey\":\"a\",\"questKeys\":[\"q\"],"
                + "\"completionRule\":{\"type\":\"ALL\"}}],\"quests\":[{\"questKey\":\"q\",\"actKey\":\"a\","
                + "\"nodeId\":1,\"locationKey\":\"gate\",\"settlementSlot\":\"q\"}]}}");
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode(1L, "城门", null)));
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter("[]")));
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishApprovedRouteSnapshot(TOPIC_ID);
        verify(topicMapper).publishRouteSnapshot(eq(TOPIC_ID), nullable(String.class), any(Date.class));
        topic.setThemeRuntimeConfigJson(legacy);
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertThrows(IllegalArgumentException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishApprovedRouteSnapshot(TOPIC_ID));
    }

    @Test
    void r5HpOutcomePublishesWithoutRegisteringSystemKeyAndFrozenFailClampsBeforeZeroPolicy() throws Exception {
        CmsTopic topic = r5OutcomeDraft("sys.hp");
        TopicRouteRuntimeServiceImpl runtime = new TopicRouteRuntimeServiceImpl(
                mock(com.chengyinhub.business.mapper.TopicRouteSessionMapper.class), topicMapper, nodeMapper, templateMapper,
                mock(com.chengyinhub.business.mapper.PlayerNodeProgressMapper.class),
                mock(com.chengyinhub.business.mapper.CmsActivityMapper.class),
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class),
                mock(com.chengyinhub.business.mapper.PlayerActivityRoleMapper.class), () -> 1L,
                java.time.Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(runtime, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(runtime, "themeStateKeyMapper", keyMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "topicRouteRuntimeService", runtime);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        String authored = templateMapper.selectCmsMemberTemplateById(101L).getAdvancedConfigJson();
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(topicMapper).publishRouteSnapshot(eq(TOPIC_ID), snapshot.capture(), any(Date.class));
        JsonNode frozen = new ObjectMapper().readTree(snapshot.getValue());
        assertEquals("sys.hp", frozen.path("nodeConfigs").path("1").path("outcomeRules").path("fail").path("costs").path(0).path("statKey").asText());
        JourneyRules rules = JourneyRules.parse(frozen.path("journeyRules").toString());
        TopicRouteEngine.State state = new TopicRouteEngine().initial(frozen.toString());
        ReflectionTestUtils.invokeMethod(runtime, "applyJourneyInitials", rules, state);
        GameplayOutcomeEngine outcomes = new GameplayOutcomeEngine();
        ObjectNode decision = new ObjectMapper().createObjectNode().put("nodeId", 1).put("outcome", "FAIL");
        ObjectNode receipt = outcomes.settle(frozen, rules, state, Collections.singletonMap(7L, state), "attempt:1", decision, 1000);
        assertEquals(0, state.getVariables().get("sys.hp"));
        assertEquals(9, receipt.path("costs").path(0).path("requested").asInt());
        assertEquals(2, receipt.path("costs").path(0).path("charged").asInt());
        new GameplaySurvivalEngine().syncZeroHp(frozen, rules, state, 1000);
        assertEquals("HP_ZERO", state.getThemeRuntime().path("result").path("reason").asText());
        assertEquals(authored, templateMapper.selectCmsMemberTemplateById(101L).getAdvancedConfigJson());
        verify(keyMapper, never()).upsertThemeStateKey(any(ThemeStateKey.class));
    }

    @Test
    void r5OutcomeRejectsUnknownSystemDomainAndUnregisteredCounter() throws Exception {
        r5OutcomeDraft("sys.foo");
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        RuntimeException unknown = assertThrows(RuntimeException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));
        assertTrue(unknown.getMessage().contains("数值绑定类型无效"), unknown.getMessage());
        r5OutcomeDraft("counter.unregistered");
        // 属性定义唯一来源是注册表（AuthorAttributeBindingTest#oldDraftsRemainEditableButCannotPublishUnconfirmedCopies）：
        // 作者只能经确认过的 attributeBinding 启用数值，发布冻结时注册表里没有这个键就拒。
        when(keyMapper.lockRegistryVersion(TOPIC_ID)).thenReturn(1L);
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        RuntimeException counter = assertThrows(RuntimeException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraftWithBinding(TOPIC_ID, 100L, 1L, "1"));
        assertTrue(counter.getMessage().contains("尚未注册") && counter.getMessage().contains("counter.unregistered"), counter.getMessage());
        verify(topicMapper, never()).publishRouteSnapshot(anyLong(), nullable(String.class), any(Date.class));
    }

    @Test
    void r5OutcomeRejectsDisabledHpAndLuckDomains() throws Exception {
        for (String target : Arrays.asList("sys.hp", "sys.luck")) {
            CmsTopic topic = r5OutcomeDraft(target);
            topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":false},\"luck\":{\"enabled\":false}}");
            com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
            RuntimeException disabled = assertThrows(RuntimeException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));
            assertTrue(disabled.getMessage().contains("未启用"), disabled.getMessage());
        }
        verify(topicMapper, never()).publishRouteSnapshot(anyLong(), nullable(String.class), any(Date.class));
    }

    private CmsTopic r5OutcomeDraft(String target) throws Exception {
        ObjectMapper json = new ObjectMapper();
        ObjectNode advanced = json.createObjectNode().put("schemaVersion", 1);
        advanced.putObject("qa").put("enabled", true).put("mode", "TYPE").put("title", "年份？").put("answerText", "1930").put("maxTries", 0);
        advanced.putObject("interactionRules").putArray("resourceSlots").addObject()
                .put("key", "vital").put("kind", "VALUE").put("label", "生命值").put("required", true);
        ObjectNode after = advanced.putObject("outcomeRules").put("version", 1);
        after.putObject("pass").put("reply", "正确");
        after.putObject("fail").put("reply", "错误").putArray("costs").addObject().put("statKey", "vital").put("amount", 9);
        CmsMemberTemplate template = template(advanced.toString()); template.setId(101L); template.setValidationMethod(0);
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
        CmsTopicNode node = topicNode(1L, "书房", 101L); node.setMerchantMemberId(310L);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        CmsTopic topic = simpleDraft(); topic.setRouteMode("LINEAR");
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":true,\"init\":2,\"max\":12}}");
        if (target.startsWith("counter.")) {
            ObjectNode rules = (ObjectNode) json.readTree(topic.getJourneyRulesJson());
            rules.putArray("attributes").addObject().put("key", target).put("label", "数值")
                    .put("initial", 2).put("min", 0).put("max", 12).put("visible", true);
            rules.putObject("attributeBinding").put("schemaVersion", 1).put("registryVersion", "1")
                    .putArray("enabledKeys").add(target);
            topic.setJourneyRulesJson(rules.toString());
        }
        ObjectNode theme = json.createObjectNode().put("schemaVersion", 1);
        theme.putObject("gameplayRuntime").put("runtimeScope", "PLAYER").putObject("survival")
                .put("zeroHpPolicy", "THEME_FAIL").put("actionLimitPolicy", "BLOCK_OVERFLOW");
        ObjectNode binding = theme.putObject("templateBindings").putObject("1").put("templateId", 101)
                .put("slotDigest", GameplayTemplateRules.slotDigest(advanced.path("interactionRules").path("resourceSlots")));
        binding.putObject("values").put("vital", target); binding.putObject("items");
        topic.setThemeRuntimeConfigJson(theme.toString());
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        return topic;
    }

    private static CmsTopic simpleDraft() {        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setPublishMode("simple");
        topic.setMemberId(100L);
        // R19-DRAFT2:可发布的主题必须有名字(草稿保存时已要求非空)。
        topic.setName("夜跑路线");
        return topic;
    }

    private static CmsTopic approvedTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setStatus(1);
        topic.setRouteMode("LINEAR");
        return topic;
    }

    private static CmsTopicNode topicNode(Long id, String name, Long templateId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(TOPIC_ID);
        node.setName(name);
        node.setTemplateId(templateId);
        node.setStatus(1L);
        return node;
    }

    private static CmsMemberTemplate template(String advancedConfigJson) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(101L); // Original fixture's explicit template binding/mapper identity.
        template.setAdvancedConfigJson(advancedConfigJson);
        return template;
    }

    private static ThemeStateKey registered(String stateKey) {
        ThemeStateKey key = new ThemeStateKey();
        key.setTopicId(TOPIC_ID);
        key.setStateKey(stateKey);
        // Explicit declaration metadata, matching the existing ThemeStateKeyService namespace contract.
        key.setKeyType(stateKey.startsWith("counter.") ? ThemeStateKey.TYPE_COUNTER
                : stateKey.startsWith("relation.") ? ThemeStateKey.TYPE_RELATION
                : stateKey.startsWith("tag.") ? ThemeStateKey.TYPE_TAG : ThemeStateKey.TYPE_FLAG);
        key.setScope(ThemeStateKey.SCOPE_RUN); key.setLabel(stateKey);
        return key;
    }
}
