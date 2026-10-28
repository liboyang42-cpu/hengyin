package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.support.MerchantNpcIdentityDirectory;
import org.mockito.ArgumentCaptor;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R11-A 发布期节点叙事 NPC 归属闸:enter / outcome 的 npcId 必须是本主题可用 NPC
 * (启用节点上的节点级 NPC 与门店分身),否则发布被拦。
 */
class CmsTopicPublishNarrativeNpcGuardTest {

    private static final Long TOPIC_ID = 10L;
    private static final Long NODE_ID = 1L;
    private static final Long TEMPLATE_ID = 101L;

    private CmsTopicMapper topicMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private CmsTopicChapterMapper chapterMapper;
    private NpcProfileMapper npcProfileMapper;
    private MmsMerchantMapper merchantMapper;
    private CmsTopicServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        when(topicMapper.selectCmsTopicByIdForUpdate(anyLong()))
                .thenAnswer(inv -> topicMapper.selectCmsTopicById(inv.getArgument(0)));
        nodeMapper = mock(CmsTopicNodeMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        npcProfileMapper = mock(NpcProfileMapper.class);
        merchantMapper = mock(MmsMerchantMapper.class);

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
        TopicStateKeyRefService references = new TopicStateKeyRefService(nodeMapper, templateMapper, chapterMapper);
        ReflectionTestUtils.setField(references, "cmsTopicMapper", topicMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "topicStateKeyRefService", references);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "npcProfileMapper", npcProfileMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "merchantNpcIdentityDirectory",
                new MerchantNpcIdentityDirectory(merchantMapper));
        // R18-DRAFT:发布时补建自办活动壳 —— 成功路径会走到这两个协作者,不在场就是 NPE 假红。
        ReflectionTestUtils.setField(service, "cmsActivityMapper",
                mock(com.chengyinhub.business.mapper.CmsActivityMapper.class));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "omsTicketService",
                mock(com.chengyinhub.business.service.IOmsTicketService.class));
        // R19-DRAFT2:发布闸读票种(完整度),不在场就是 NPE 假红。
        ReflectionTestUtils.setField(service, "omsTicketMapper",
                mock(com.chengyinhub.business.mapper.OmsTicketMapper.class));
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        when(topicMapper.publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class))).thenReturn(1);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "themeStateKeyService",
                new com.chengyinhub.business.service.support.ThemeStateKeyService(
                        mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class)));
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        service = com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service);
    }

    /**
     * enter.npcId 指向本主题外 NPC ⇒ 发布失败,且报出节点与段。
     *
     * <p>负控:去掉 publishDraft / publishApprovedRouteSnapshot 里的 assertBlockNarrativeNpcReferences
     * 调用,本用例立刻红(坏 npcId 被静默发布)。
     */
    @Test
    void foreignBlockNarrativeNpcBlocksPublish() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode()));
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(
                "{\"enter\":{\"npcId\":99,\"opener\":\"风\"},\"outcome\":{\"npcId\":5,\"title\":\"夜\"}}"));
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template(
                "{\"enter\":{\"npcId\":99,\"opener\":\"风\"},\"outcome\":{\"npcId\":5,\"title\":\"夜\"}}")));
        // 本主题可用 NPC 只有 5 号(节点 1 的节点级启用 NPC)
        when(npcProfileMapper.selectEnabledByScope(NpcProfileService.SCOPE_NODE, NODE_ID))
                .thenReturn(Collections.singletonList(profile(5L)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("npcId=99"), error.getMessage());
        assertTrue(error.getMessage().contains("enter"), error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }

    /** npcId 落在本主题可用名单里 ⇒ 照常发布。 */
    @Test
    void availableBlockNarrativeNpcPublishes() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode()));
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(
                "{\"enter\":{\"npcId\":5,\"opener\":\"风\"}}"));
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template(
                "{\"enter\":{\"npcId\":5,\"opener\":\"风\"}}")));
        when(npcProfileMapper.selectEnabledByScope(NpcProfileService.SCOPE_NODE, NODE_ID))
                .thenReturn(Collections.singletonList(profile(5L)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(topicMapper).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    /** 老主题(块配置没有叙事 npcId)连 NPC 名单都不查,行为不变。 */
    @Test
    void legacyBlockWithoutNarrativeNpcDoesNotTouchNpcProfiles() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode()));
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true}}"));
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true}}")));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(npcProfileMapper, never()).selectEnabledByScope(anyInt(), anyLong());
    }

    /**
     * R18:叙事段迁到故事流后,beat 块上的 npcId 必须过同一道归属闸 ——
     * 否则故事流里配了本主题外的 NPC 会静默发布,玩家侧只看到 npc=null。
     */
    @Test
    void foreignStoryFlowNarrativeNpcBlocksPublish() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode()));
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true}}"));
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true}}")));
        useStoryFlowBlocks("[{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\",\"content\":\"风\","
                + "\"npcId\":99,\"nodeId\":" + NODE_ID + "}]");
        when(npcProfileMapper.selectEnabledByScope(NpcProfileService.SCOPE_NODE, NODE_ID))
                .thenReturn(Collections.singletonList(profile(5L)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("npcId=99"), error.getMessage());
        assertTrue(error.getMessage().contains("故事流"), error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }

    /** 故事流块的 npcId 在本主题可用名单里 ⇒ 照常发布。 */
    @Test
    void availableStoryFlowNarrativeNpcPublishes() {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(topicNode()));
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true}}"));
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true}}")));
        useStoryFlowBlocks("[{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"line\",\"content\":\"夜\","
                + "\"npcId\":5,\"nodeId\":" + NODE_ID + "}]");
        when(npcProfileMapper.selectEnabledByScope(NpcProfileService.SCOPE_NODE, NODE_ID))
                .thenReturn(Collections.singletonList(profile(5L)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(topicMapper).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    @Test
    void merchantBlockNarrativeUsesMerchantRowIdInsteadOfOwnerMemberId() {
        merchantBlock(5L);
        when(merchantMapper.selectMmsMerchantList(any(MmsMerchant.class)))
                .thenReturn(Collections.singletonList(availableMerchant()));
        NpcProfile merchantNpc = profile(5L); merchantNpc.setScopeType(2); merchantNpc.setScopeId(700L);
        when(npcProfileMapper.selectEnabledByScope(2, 700L)).thenReturn(Collections.singletonList(merchantNpc));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        ArgumentCaptor<MmsMerchant> query = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(merchantMapper).selectMmsMerchantList(query.capture());
        assertEquals(Long.valueOf(210L), query.getValue().getMemberId());
        verify(npcProfileMapper).selectEnabledByScope(2, 700L);
        verify(npcProfileMapper, never()).selectEnabledByScope(2, 210L);
        verify(topicMapper).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    @Test
    void ownerMemberNumericAliasCannotMakeAForeignMerchantNpcAvailable() {
        merchantBlock(99L);
        when(merchantMapper.selectMmsMerchantList(any(MmsMerchant.class)))
                .thenReturn(Collections.singletonList(availableMerchant()));
        when(npcProfileMapper.selectEnabledByScope(2, 700L)).thenReturn(Collections.singletonList(profile(5L)));
        // This decoy would authorize the old member-id-as-scope-id bug.
        when(npcProfileMapper.selectEnabledByScope(2, 210L)).thenReturn(Collections.singletonList(profile(99L)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("npcId=99"), error.getMessage());
        assertTrue(error.getMessage().contains("enter"), error.getMessage());
        verify(npcProfileMapper, never()).selectEnabledByScope(2, 210L);
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    @Test
    void unavailableMerchantCannotContributeItsScopeTwoNpc() {
        merchantBlock(5L);
        MmsMerchant disabled = availableMerchant(); disabled.setAccountStatus(0L);
        when(merchantMapper.selectMmsMerchantList(any(MmsMerchant.class)))
                .thenReturn(Collections.singletonList(disabled));
        when(npcProfileMapper.selectEnabledByScope(2, 700L)).thenReturn(Collections.singletonList(profile(5L)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        ServiceException error = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        assertTrue(error.getMessage().contains("npcId=5"), error.getMessage());
        verify(npcProfileMapper, never()).selectEnabledByScope(2, 700L);
        verify(npcProfileMapper, never()).selectEnabledByScope(2, 210L);
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    @Test
    void merchantLookupFailurePropagatesWithoutPublishingOrNumericFallback() {
        merchantBlock(5L);
        IllegalStateException original = new IllegalStateException("controlled directory storage refusal");
        when(merchantMapper.selectMmsMerchantList(any(MmsMerchant.class))).thenThrow(original);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertSame(original, assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID)));

        verify(npcProfileMapper, never()).selectEnabledByScope(2, 700L);
        verify(npcProfileMapper, never()).selectEnabledByScope(2, 210L);
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    @Test
    void journeyStoryMerchantSpeakerUsesTheSameResolvedScopeTwoIdentity() {
        merchantBlock(5L);
        CmsTopic topic = topicMapper.selectCmsTopicById(TOPIC_ID);
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"call\":{\"npcId\":5,\"opener\":\"开场\"},"
                + "\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"when\":null,\"text\":\"a\"}]}}]}");
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID))
                .thenReturn(template("{\"schemaVersion\":1,\"timer\":{\"enabled\":true}}"));
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(
                template("{\"schemaVersion\":1,\"timer\":{\"enabled\":true}}")));
        CmsTopicNode node = topicNode(); node.setMerchantMemberId(210L); node.setChapterId(11L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Collections.singletonList(node));
        useStoryFlowBlocks("[]");
        when(merchantMapper.selectMmsMerchantList(any(MmsMerchant.class)))
                .thenReturn(Collections.singletonList(availableMerchant()));
        NpcProfile merchantNpc = profile(5L); merchantNpc.setScopeType(2); merchantNpc.setScopeId(700L);
        when(npcProfileMapper.selectEnabledByScope(2, 700L)).thenReturn(Collections.singletonList(merchantNpc));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service).publishDraft(TOPIC_ID));

        verify(npcProfileMapper).selectEnabledByScope(2, 700L);
        verify(npcProfileMapper, never()).selectEnabledByScope(2, 210L);
        verify(topicMapper).publishRouteSnapshot(any(Long.class), nullable(String.class), any(Date.class));
    }

    private void merchantBlock(long npcId) {
        when(topicMapper.clearDraftIfDraft(TOPIC_ID)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(simpleDraft());
        CmsTopicNode node = topicNode(); node.setMerchantMemberId(210L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Collections.singletonList(node));
        CmsMemberTemplate block = template("{\"enter\":{\"npcId\":" + npcId + ",\"opener\":\"风\"}}");
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(block);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(block));
    }

    private static MmsMerchant availableMerchant() {
        MmsMerchant row = new MmsMerchant();
        row.setId(700L); row.setMemberId(210L); row.setStatus(1L); row.setAccountStatus(1L); row.setDelFlag(0);
        return row;
    }

    private void useStoryFlowBlocks(String blocksJson) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(11L);
        chapter.setTopicId(TOPIC_ID);
        chapter.setBlocksJson(blocksJson);
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter));
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
    }

    private static CmsTopic simpleDraft() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setPublishMode("simple");
        topic.setMemberId(100L);
        // R19-DRAFT2:可发布的主题必须有名字(草稿保存时已要求非空)。
        topic.setName("夜跑路线");
        return topic;
    }

    private static CmsTopicNode topicNode() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setName("书房");
        node.setTemplateId(TEMPLATE_ID);
        node.setStatus(1L);
        return node;
    }

    private static CmsMemberTemplate template(String advancedConfigJson) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setAdvancedConfigJson(advancedConfigJson);
        return template;
    }

    private static NpcProfile profile(Long id) {
        NpcProfile profile = new NpcProfile();
        profile.setId(id);
        profile.setScopeType(NpcProfileService.SCOPE_NODE);
        profile.setScopeId(NODE_ID);
        return profile;
    }
}
