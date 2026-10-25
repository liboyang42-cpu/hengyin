package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R8-STORY:剧情配置的发布期两道闸 —— cond / optionalClues 引用未注册键必须被拦;
 * stages 的 chapterId 与 epilogues 的 npcId 引用必须能对上主题现码。老主题(无剧情)行为不变。
 */
class TopicPublishJourneyStoryGateTest {

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
    private static final Long CHAPTER = 11L;

    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private ThemeStateKeyMapper keyMapper;
    private NpcProfileMapper npcProfileMapper;
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
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        keyMapper = mock(ThemeStateKeyMapper.class);
        when(keyMapper.lockTopicForStateKeyWrite(TOPIC_ID)).thenAnswer(call -> {
            CmsTopic parent = topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID);
            return parent == null ? null : parent.getId();
        });
        when(keyMapper.selectByTopicIdForUpdate(TOPIC_ID)).thenAnswer(call -> new java.util.ArrayList<>(currentDeclarations));
        npcProfileMapper = mock(NpcProfileMapper.class);

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
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "cmsMemberTemplateMapper", templateMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "npcProfileMapper", npcProfileMapper);
        ReflectionTestUtils.setField(service, "themeStateKeyService", new ThemeStateKeyService(keyMapper));
        com.chengyinhub.business.service.support.TopicStateKeyRefService references =
                new com.chengyinhub.business.service.support.TopicStateKeyRefService(nodeMapper, templateMapper, chapterMapper);
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
        when(topicMapper.publishRouteSnapshot(anyLong(), any(), any())).thenReturn(1);
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(draft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node(501L)));
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter(CHAPTER)));
    }

    @Test
    void unregisteredStoryKeyBlocksPublishWithSourceLabel() {
        story("{\"schemaVersion\":1,\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":["
                + "{\"when\":{\"var\":\"clue.page\",\"op\":\"GTE\",\"value\":1},\"text\":\"a\"}]}}]}");
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("clue.page"), error.getMessage());
        assertTrue(error.getMessage().contains("剧情配置"), "要说明这个键是剧情配置要的:" + error.getMessage());
        verify(topicMapper, never()).publishRouteSnapshot(anyLong(), any(), any());
    }

    /** optionalClues 与 cond 同一道闸:未注册的线索键同样拦发布,并点名来源。 */
    @Test
    void unregisteredOptionalClueBlocksPublish() {
        story("{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"optionalClues\":[\"clue.voice\"]}}");
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("clue.voice"), error.getMessage());
        assertTrue(error.getMessage().contains("剧情配置"), error.getMessage());
        verify(topicMapper, never()).publishRouteSnapshot(anyLong(), any(), any());
    }

    @Test
    void registeredStoryKeysPublish() {
        story("{\"schemaVersion\":1,\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":["
                + "{\"when\":{\"var\":\"clue.page\",\"op\":\"GTE\",\"value\":1},\"text\":\"a\"}]}}],"
                + "\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"optionalClues\":[\"clue.voice\"]}}");
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Arrays.asList(
                registered("clue.page"), registered("tag.hurried"), registered("clue.voice"))));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(topicMapper).publishRouteSnapshot(anyLong(), any(), any());
    }

    @Test
    void storyWithoutAnyConditionDoesNotTouchRegistry() {
        story("{\"schemaVersion\":1,\"call\":{\"npcId\":7,\"opener\":\"开场\"},"
                + "\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"when\":null,\"text\":\"a\"}]}}]}");
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node(501L)));
        NpcProfile npc = new NpcProfile();
        npc.setId(7L);
        when(npcProfileMapper.selectEnabledByScope(4, 501L)).thenReturn(Collections.singletonList(npc));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(keyMapper, never()).selectByTopicId(anyLong());
    }

    @Test
    void storyChapterMustExistInTopicAtPublishTime() {
        story("{\"schemaVersion\":1,\"stages\":[{\"chapterId\":99,\"end\":{}}]}");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter(CHAPTER)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("引用无效"), error.getMessage());
        assertTrue(error.getMessage().contains("章节"), error.getMessage());
    }

    @Test
    void storyNpcMustBeAvailableInTopicAtPublishTime() {
        story("{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.arong\","
                + "\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}}");
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.singletonList(
                registered("relation.arong", ThemeStateKey.TYPE_RELATION))));
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node(501L)));
        NpcProfile available = new NpcProfile();
        available.setId(9L);
        when(npcProfileMapper.selectEnabledByScope(4, 501L)).thenReturn(Collections.singletonList(available));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("引用无效"), error.getMessage());
        assertTrue(error.getMessage().contains("NPC"), error.getMessage());
    }

    /** R11-C:尾声 relationKey 引用的键没注册 → 发布被拦,来源标剧情配置。 */
    @Test
    void unregisteredEpilogueRelationKeyBlocksPublish() {
        story("{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.arong\","
                + "\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}}");
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.<ThemeStateKey>emptyList()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("relation.arong"), error.getMessage());
        assertTrue(error.getMessage().contains("剧情配置"), error.getMessage());
        verify(topicMapper, never()).publishRouteSnapshot(anyLong(), any(), any());
    }

    /** R11-C:键注册了但类型不是 relation(关系值来源) → 发布仍被拦。 */
    @Test
    void epilogueRelationKeyWithWrongTypeBlocksPublish() {
        story("{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.arong\","
                + "\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}}");
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.singletonList(
                registered("relation.arong", ThemeStateKey.TYPE_FLAG))));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("relation.arong"), error.getMessage());
        assertTrue(error.getMessage().contains("relation"), error.getMessage());
        verify(topicMapper, never()).publishRouteSnapshot(anyLong(), any(), any());
    }

    /** R11-C:注册为 relation 类型的 relationKey 才能放行。 */
    @Test
    void registeredRelationTypeEpilogueKeyPublishes() {
        story("{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.arong\","
                + "\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}}");
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(currentRows(Collections.singletonList(
                registered("relation.arong", ThemeStateKey.TYPE_RELATION))));
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node(501L)));
        NpcProfile available = new NpcProfile();
        available.setId(7L);
        when(npcProfileMapper.selectEnabledByScope(4, 501L)).thenReturn(Collections.singletonList(available));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(topicMapper).publishRouteSnapshot(anyLong(), any(), any());
    }

    @Test
    void legacyTopicWithoutStoryPublishesUntouched() {
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(keyMapper, never()).selectByTopicId(anyLong());
    }

    private void story(String storyJson) {
        topicMapper.selectCmsTopicById(TOPIC_ID).setJourneyStoryJson(storyJson);
    }

    // ==================== 思维阁:念头引用 ====================
    // thought 块只放引用,念头的定义在 journeyRules。编译器拿不到 journeyRules,
    // 所以「引用了不存在的念头」这层兜不住 —— 必须在发布闸拦,否则玩家永远得不到那个念头,
    // 而且零报错(与 sys_job 没注册同一类静默失败)。

    @Test
    void thoughtBlockReferencingUnknownThoughtBlocksPublish() {
        CmsTopic topic = draft();
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"thoughts\":[{\"key\":\"afternoon\",\"name\":\"那个下午\",\"need\":120}]}");
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        CmsTopicChapter withThought = chapter(CHAPTER);
        withThought.setBlocksJson("[{\"type\":\"thought\",\"key\":\"t1\",\"thoughtKey\":\"nosuch\"}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(withThought));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));
        assertTrue(error.getMessage().contains("念头"), error.getMessage());
        assertTrue(error.getMessage().contains("nosuch"), "要报出是哪个 key 对不上:" + error.getMessage());
    }

    @Test
    void thoughtBlockWithRegisteredKeyPublishesFine() {
        CmsTopic topic = draft();
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"thoughts\":[{\"key\":\"afternoon\",\"name\":\"那个下午\",\"need\":120}]}");
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        CmsTopicChapter withThought = chapter(CHAPTER);
        withThought.setBlocksJson("[{\"type\":\"thought\",\"key\":\"t1\",\"thoughtKey\":\"afternoon\"}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(withThought));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));
    }

    private static CmsTopic draft() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setPublishMode("simple");
        topic.setMemberId(100L);
        // R19-DRAFT2:可发布的主题必须有名字(草稿保存时已要求非空)。
        topic.setName("夜跑路线");
        return topic;
    }

    private static CmsTopicChapter chapter(Long id) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(id);
        chapter.setTopicId(TOPIC_ID);
        return chapter;
    }

    private static CmsTopicNode node(Long id) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(TOPIC_ID);
        node.setChapterId(CHAPTER);
        node.setStatus(1L);
        return node;
    }

    private static ThemeStateKey registered(String stateKey) {
        return registered(stateKey, null);
    }

    private static ThemeStateKey registered(String stateKey, String keyType) {
        ThemeStateKey key = new ThemeStateKey();
        key.setStateKey(stateKey);
        key.setTopicId(TOPIC_ID);
        key.setScope(ThemeStateKey.SCOPE_RUN); key.setLabel(stateKey);
        key.setKeyType(keyType == null
                ? (stateKey.startsWith("tag.") ? ThemeStateKey.TYPE_TAG : ThemeStateKey.TYPE_FLAG) : keyType);
        return key;
    }
}
