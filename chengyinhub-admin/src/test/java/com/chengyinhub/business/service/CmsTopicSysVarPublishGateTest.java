package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

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

/**
 * R14-PLAY 发布期闸:块配置条件里的 sys.* nodeId 必须属于本主题;剧情 dispositionNodeId
 * 必须属于本主题且是分支玩法。sys.* 本身不进键注册表,不要求注册。
 *
 * <p>负控:去掉 assertBlockSysVarReferences / disposition 的分支核对,本类对应用例立红。
 */
class CmsTopicSysVarPublishGateTest {

    // Unit call context only: mapper doubles are not MySQL/Spring rollback evidence.
    private boolean publicationWasActive, publicationWasReadOnly;
    @org.junit.jupiter.api.BeforeEach
    void enterPublicationWriteContext() {
        publicationWasActive = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        publicationWasReadOnly = org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
    @org.junit.jupiter.api.AfterEach
    void restorePublicationWriteContext() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(publicationWasActive);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(publicationWasReadOnly);
    }


    private static final Long TOPIC_ID = 10L;
    private static final Long NODE_BRANCH = 5009L;
    private static final Long NODE_PLAIN = 5008L;

    private CmsTopicMapper topicMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsMemberTemplateMapper templateMapper;
    private ThemeStateKeyMapper keyMapper;
    private CmsTopicServiceImpl service;
    private final java.util.List<ThemeStateKey> currentDeclarations = new java.util.ArrayList<>();

    private java.util.List<ThemeStateKey> currentRows(java.util.List<ThemeStateKey> rows) {
        currentDeclarations.clear(); currentDeclarations.addAll(rows); return rows;
    }


    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        when(topicMapper.selectCmsTopicByIdForUpdate(anyLong()))
                .thenAnswer(inv -> topicMapper.selectCmsTopicById(inv.getArgument(0)));
        nodeMapper = mock(CmsTopicNodeMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        keyMapper = mock(ThemeStateKeyMapper.class);
        when(keyMapper.lockTopicForStateKeyWrite(TOPIC_ID)).thenAnswer(call -> {
            CmsTopic parent = topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID);
            return parent == null ? null : parent.getId();
        });
        when(keyMapper.selectByTopicIdForUpdate(TOPIC_ID)).thenAnswer(call -> new java.util.ArrayList<>(currentDeclarations));

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
        TopicStateKeyRefService references = new TopicStateKeyRefService(nodeMapper, templateMapper, chapterMapper);
        ReflectionTestUtils.setField(references, "cmsTopicMapper", topicMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "topicStateKeyRefService", references);
        // R18-DRAFT 起 publishDraft 会补建自办活动壳:壳已存在 / 票种查询是发布路径的前置协作者。
        CmsActivityMapper cmsActivityMapper = mock(CmsActivityMapper.class);
        CmsActivity existingShell = new CmsActivity();
        existingShell.setId(900L);
        when(cmsActivityMapper.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.singletonList(existingShell));
        ReflectionTestUtils.setField(service, "cmsActivityMapper", cmsActivityMapper);
        OmsTicketMapper omsTicketMapper = mock(OmsTicketMapper.class);
        when(omsTicketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.<OmsTicket>emptyList());
        ReflectionTestUtils.setField(service, "omsTicketMapper", omsTicketMapper);
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        when(topicMapper.publishRouteSnapshot(eq(TOPIC_ID), nullable(String.class), any(Date.class))).thenReturn(1);
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.<CmsTopicChapter>emptyList());
    }

    @Test
    void blockSysVarNodeOutsideThemeBlocksPublish() {
        publishDraftWithBlock("{\"requires\":["
                + "{\"var\":\"sys.outcome.9999\",\"op\":\"EQ\",\"value\":1}]}");

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("系统变量"), error.getMessage());
        assertTrue(error.getMessage().contains("9999"), error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }

    /** 负控:sys.* 被当成普通键要求注册时本用例红(sys.* 不进注册表)。 */
    @Test
    void blockSysVarNodeInsideThemePublishesWithoutKeyRegistration() {
        publishDraftWithBlock("{\"requires\":["
                + "{\"var\":\"sys.outcome.5009\",\"op\":\"EQ\",\"value\":1}]}");
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(topicMapper).publishRouteSnapshot(eq(TOPIC_ID), nullable(String.class), any(Date.class));
    }

    /**
     * 阶段 1:sys.passed.{nodeId} 与 sys.outcome 同口径 —— 引用非本主题节点(9999)时发布期必须拦,
     * 否则这条条件永远不成立却悄悄上线。负控:addSysNodeId 不认 passed 时,9999 收不进集合,本用例红。
     */
    @Test
    void blockPassedSysVarNodeOutsideThemeBlocksPublish() {
        publishDraftWithBlock("{\"requires\":["
                + "{\"var\":\"sys.passed.9999\",\"op\":\"EQ\",\"value\":1}]}");

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("系统变量"), error.getMessage());
        assertTrue(error.getMessage().contains("9999"), error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }

    /** sys.passed 引用本主题节点(5009):放行且不要求注册(与其余 sys.* 同)。 */
    @Test
    void blockPassedSysVarNodeInsideThemePublishes() {
        publishDraftWithBlock("{\"requires\":["
                + "{\"var\":\"sys.passed.5009\",\"op\":\"EQ\",\"value\":1}]}");
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));
    }

    /**
     * R22-SEC S16:NODE_COMPLETED 引用本主题之外的节点 = 条件永远不成立,发布期必须拦。
     *
     * <p>负控:去掉 assertBlockSysVarReferences 里的 assertCompletedNodeIdsOwned 调用,本用例红。
     */
    @Test
    void blockNodeCompletedOutsideThemeBlocksPublish() {
        publishDraftWithBlock("{\"schemaVersion\":1,\"requires\":["
                + "{\"op\":\"NODE_COMPLETED\",\"nodeId\":9999}]}");

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("NODE_COMPLETED"), error.getMessage());
        assertTrue(error.getMessage().contains("9999"), error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void blockNodeCompletedInsideThemePublishes() {
        publishDraftWithBlock("{\"schemaVersion\":1,\"requires\":["
                + "{\"op\":\"NODE_COMPLETED\",\"nodeId\":5009}]}");
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));
    }

    @Test
    void journeyStoryDispositionNodeMustBeBranch() {
        CmsTopic topic = simpleDraft();
        topic.setJourneyStoryJson(storyJson(NODE_PLAIN));
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        stubNodes(plainTemplate(), branchTemplate());
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.singletonList(registered("counter.disposition"))));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("分支玩法"), error.getMessage());
    }

    @Test
    void journeyStoryDispositionNodeBranchPublishes() {
        CmsTopic topic = simpleDraft();
        topic.setJourneyStoryJson(storyJson(NODE_BRANCH));
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        stubNodes(plainTemplate(), branchTemplate());
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.singletonList(registered("counter.disposition"))));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));
    }

    private void publishDraftWithBlock(String advancedConfigJson) {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode(NODE_BRANCH, "第九站", 101L)));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template(101L, advancedConfigJson));
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(template(101L, advancedConfigJson)));
    }

    private void stubNodes(CmsMemberTemplate plain, CmsMemberTemplate branch) {
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(
                topicNode(NODE_BRANCH, "第九站", 101L), topicNode(NODE_PLAIN, "第八站", 102L)));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(branch);
        when(templateMapper.selectCmsMemberTemplateById(102L)).thenReturn(plain);
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Arrays.asList(branch, plain));
    }

    private static ThemeStateKey registered(String stateKey) {
        ThemeStateKey key = new ThemeStateKey();
        key.setStateKey(stateKey);
        key.setTopicId(TOPIC_ID);
        key.setKeyType(ThemeStateKey.TYPE_COUNTER);
        key.setScope(ThemeStateKey.SCOPE_RUN); key.setLabel(stateKey);
        return key;
    }

    private static CmsTopic simpleDraft() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setName("系统变量闸测试主题");
        topic.setPublishMode("simple");
        topic.setMemberId(100L);
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

    private static CmsMemberTemplate template(Long id, String advancedConfigJson) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(id);
        template.setAdvancedConfigJson(advancedConfigJson);
        return template;
    }

    private static CmsMemberTemplate plainTemplate() {
        return template(102L, "{\"schemaVersion\":1,\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"easy\"}}");
    }

    private static CmsMemberTemplate branchTemplate() {
        return template(101L, "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"ask\",\"steps\":["
                + "{\"id\":\"ask\",\"options\":[{\"id\":\"a\",\"label\":\"甲\",\"nextStepId\":\"done\","
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"counter.disposition\",\"value\":1}]}]},"
                + "{\"id\":\"done\",\"terminal\":true,\"outcomeCode\":\"done\",\"options\":[]}]}}");
    }

    private static String storyJson(Long dispositionNodeId) {
        return "{\"schemaVersion\":1,\"ending\":{\"dispositionNodeId\":" + dispositionNodeId + ",\"endings\":["
                + "{\"code\":\"a\",\"title\":\"更正\",\"when\":["
                + "{\"var\":\"sys.outcome." + dispositionNodeId + "\",\"op\":\"EQ\",\"value\":1}]},"
                + "{\"code\":\"default\",\"title\":\"暂缓\",\"fallback\":true}]}}";
    }
}
