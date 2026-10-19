package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.LegacyCmsProvenanceMapper;
import com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.TopicEditionReopenRequest;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.mapper.TopicEditionReopenMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.NodeNpcService;
import com.chengyinhub.business.service.impl.TopicEditionReopenServiceImpl;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R13-NPC:重开一期 —— 新期节点、节点 NPC 都是新 id,剧情 call/epilogues 与节点模板
 * enter/outcome.npcId 必须指向新期 NPC;门店分身不随期复制 -> 置空并告警。
 *
 * <p>归属未变时模板本来是跨期共享的,含 NPC 引用时必须给新期深拷贝一份再回写,
 * 否则会把来源期的引用一起改掉。
 */
class TopicReopenNpcRemapTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Long SOURCE = 100L;
    private static final Long NEW_TOPIC = 200L;
    private static final Long SOURCE_CHAPTER = 101L;
    private static final Long NEW_CHAPTER = 201L;
    private static final Long SOURCE_NODE = 102L;
    private static final Long NEW_NODE = 202L;
    private static final Long PLATFORM_MEMBER = 900L;
    private static final Long SOURCE_TEMPLATE = 701L;
    private static final Long COPIED_TEMPLATE = 801L;
    private static final String READY_VOICE_ID = "cosyvoice-v2-copy-ready";
    private static final String READY_VOICE_SAMPLE = "/media/sample-501.wav";
    private static final String TEMPLATE_JSON = "{\"schemaVersion\":1,"
            + "\"enter\":{\"npcId\":501,\"opener\":\"来了\"}}";
    private static final String STORY = "{\"schemaVersion\":1,"
            + "\"call\":{\"npcId\":501,\"opener\":\"喂\"},"
            + "\"stages\":[{\"chapterId\":101}],"
            + "\"ending\":{\"epilogues\":[{\"npcId\":601,\"ally\":\"a\",\"neutral\":\"n\",\"guarded\":\"g\"}]}}";

    private final List<NpcProfile> insertedNpcs = new ArrayList<NpcProfile>();
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(TopicEditionReopenServiceImpl.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<ILoggingEvent>();

    private CmsMemberTemplateMapper templateMapper;
    private TopicEditionReopenMapper reopenMapper;
    private NpcProfileMapper npcMapper;
    private TopicEditionReopenServiceImpl service;

    @BeforeEach
    void setUp() {
        logAppender.start();
        serviceLogger.addAppender(logAppender);

        reopenMapper = mock(TopicEditionReopenMapper.class);
        ProductRoleMapper productRoleMapper = mock(ProductRoleMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        npcMapper = mock(NpcProfileMapper.class);
        service = new TopicEditionReopenServiceImpl(reopenMapper, productRoleMapper, templateMapper,
                mock(ClubMapper.class), mock(UmsMemberMapper.class), npcMapper);
        ReflectionTestUtils.setField(service, "platformMemberId", PLATFORM_MEMBER);
        LegacyCmsProvenanceMapper provenance = mock(LegacyCmsProvenanceMapper.class);
        when(provenance.selectLegacyBlockState(701L)).thenReturn(0);
        ReflectionTestUtils.setField(service, "legacyCmsProvenanceGuard", new LegacyCmsProvenanceGuard(provenance));
        com.chengyinhub.business.mapper.MerchantAuthoringCopyMapper authoringCopies =
                mock(com.chengyinhub.business.mapper.MerchantAuthoringCopyMapper.class);
        when(authoringCopies.selectCopyBlockState(701L)).thenReturn(0);
        ReflectionTestUtils.setField(service, "merchantAuthoringCopyGuard",
                new com.chengyinhub.business.service.support.MerchantAuthoringCopyGuard(authoringCopies));

        when(reopenMapper.insertReopenRequest(any(TopicEditionReopenRequest.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, TopicEditionReopenRequest.class).setId(8010L);
            return 1;
        });
        CmsTopic source = new CmsTopic();
        source.setId(SOURCE);
        source.setDelFlag(0);
        source.setLifecycle(TopicLifecycleState.SELLING.getCode());
        source.setProductType(2);
        source.setJourneyStoryJson(STORY);
        when(reopenMapper.selectSourceTopicForUpdate(SOURCE)).thenReturn(source);
        ProductRole content = new ProductRole();
        content.setSlot(ProductSlot.CONTENT);
        content.setEntityType(RoleEntityType.PLATFORM);
        content.setStatus(1);
        when(reopenMapper.selectSourceContentForUpdate(SOURCE)).thenReturn(content);

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(SOURCE_CHAPTER);
        chapter.setTopicId(SOURCE);
        chapter.setSortId(1L);
        when(reopenMapper.selectSourceChapters(SOURCE)).thenReturn(Collections.singletonList(chapter));

        CmsTopicNode node = new CmsTopicNode();
        node.setId(SOURCE_NODE);
        node.setTopicId(SOURCE);
        node.setChapterId(SOURCE_CHAPTER);
        node.setName("旧码头");
        node.setTemplateId(SOURCE_TEMPLATE);
        node.setStatus(1L);
        when(reopenMapper.selectSourceNodes(SOURCE)).thenReturn(Collections.singletonList(node));

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(SOURCE_TEMPLATE);
        template.setMemberId(PLATFORM_MEMBER);
        template.setStatus(1L);
        template.setDraftStatus(1);
        template.setAdvancedConfigJson(TEMPLATE_JSON);
        when(templateMapper.selectCmsMemberTemplateById(SOURCE_TEMPLATE)).thenReturn(template);
        when(templateMapper.insertCmsMemberTemplate(any(CmsMemberTemplate.class))).thenAnswer(invocation -> {
            ((CmsMemberTemplate) invocation.getArgument(0)).setId(COPIED_TEMPLATE);
            return 1;
        });
        when(templateMapper.updateCmsMemberTemplate(any(CmsMemberTemplate.class))).thenReturn(1);

        when(reopenMapper.insertReopenedTopic(any(CmsTopic.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CmsTopic.class).setId(NEW_TOPIC);
            return 1;
        });
        when(reopenMapper.insertReopenedChapter(any(CmsTopicChapter.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CmsTopicChapter.class).setId(NEW_CHAPTER);
            return 1;
        });
        when(reopenMapper.insertReopenedNode(any(CmsTopicNode.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CmsTopicNode.class).setId(NEW_NODE);
            return 1;
        });
        when(productRoleMapper.insertProductRole(any(ProductRole.class))).thenReturn(1);
        when(reopenMapper.updateReopenedChapterBlocks(anyLong(), any(String.class))).thenReturn(1);
        when(reopenMapper.updateReopenedJourneyStory(eq(NEW_TOPIC), any(String.class))).thenReturn(1);
        when(reopenMapper.completeReopenRequest(anyLong(), anyLong())).thenReturn(1);

        NpcProfile readyNpc = npc(501L, SOURCE_NODE);
        readyNpc.setVoiceId(READY_VOICE_ID);
        readyNpc.setVoiceStatus(NodeNpcService.VOICE_READY);
        readyNpc.setVoiceSample(READY_VOICE_SAMPLE);
        when(npcMapper.selectNodeNpcByNodeId(SOURCE_NODE)).thenReturn(readyNpc);
        AtomicLong npcIds = new AtomicLong(9500L);
        when(npcMapper.insertNodeNpc(any(NpcProfile.class))).thenAnswer(invocation -> {
            NpcProfile inserted = invocation.getArgument(0, NpcProfile.class);
            inserted.setId(npcIds.incrementAndGet());
            insertedNpcs.add(inserted);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    @Test
    void reopenRemapsNpcRefsToNewEditionNpcs() throws Exception {
        assertEquals(NEW_TOPIC, service.reopenNextEdition(SOURCE, "edition-r13-npc"));

        ArgumentCaptor<CmsTopicNode> node = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(reopenMapper).insertReopenedNode(node.capture());
        assertEquals(COPIED_TEMPLATE, node.getValue().getTemplateId(),
                "含 NPC 引用的模板必须为新期深拷贝,不能沿用共享的 " + SOURCE_TEMPLATE);

        assertEquals(1, insertedNpcs.size(), "节点 NPC 必须随新期节点复制");
        assertEquals(NEW_NODE, insertedNpcs.get(0).getScopeId());
        assertEquals(Long.valueOf(9501L), insertedNpcs.get(0).getId());

        ArgumentCaptor<CmsMemberTemplate> templatePatch = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(templateMapper).updateCmsMemberTemplate(templatePatch.capture());
        JsonNode block = JSON.readTree(templatePatch.getValue().getAdvancedConfigJson());
        assertEquals(9501L, block.path("enter").path("npcId").asLong(),
                "新期模板 enter.npcId 必须指向新期 NPC");

        ArgumentCaptor<String> story = ArgumentCaptor.forClass(String.class);
        verify(reopenMapper).updateReopenedJourneyStory(eq(NEW_TOPIC), story.capture());
        JsonNode storyJson = JSON.readTree(story.getValue());
        assertEquals(9501L, storyJson.path("call").path("npcId").asLong());
        assertTrue(storyJson.path("ending").path("epilogues").get(0).path("npcId").isNull(),
                "门店分身不随期复制:epilogues 引用必须置空");

        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("=601")), "置空必须有告警");

        assertReadyVoiceCopied(insertedNpcs.get(0));
    }

    /**
     * R19:叙事搬进章节故事流后,beat 块 npcId 与模板 enter / outcome 同一口径换成新期 NPC;
     * 门店分身不随期复制 → 置空并告警。nodeId 同步换新期节点。
     *
     * <p>负控:删掉 remapBlocks 里的 remapChapterBlockNpcIds 调用,本用例立刻红。
     */
    @Test
    void reopenRemapsChapterStoryFlowBeatNpcIds() throws Exception {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(SOURCE_CHAPTER);
        chapter.setTopicId(SOURCE);
        chapter.setSortId(1L);
        chapter.setBlocksJson("[{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\","
                + "\"nodeId\":102,\"npcId\":501,\"content\":\"来了\"},"
                + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"line\","
                + "\"nodeId\":102,\"npcId\":601,\"content\":\"再见\"},"
                + "{\"type\":\"node\",\"nodeId\":102}]");
        when(reopenMapper.selectSourceChapters(SOURCE)).thenReturn(Collections.singletonList(chapter));

        assertEquals(NEW_TOPIC, service.reopenNextEdition(SOURCE, "edition-r19-storyflow"));

        ArgumentCaptor<String> blocks = ArgumentCaptor.forClass(String.class);
        verify(reopenMapper).updateReopenedChapterBlocks(eq(NEW_CHAPTER), blocks.capture());
        JsonNode stored = JSON.readTree(blocks.getValue());
        assertEquals(9501L, stored.get(0).path("npcId").asLong(),
                "beat 块 npcId 必须指向新期 NPC,不得留在来源期");
        assertEquals(202L, stored.get(0).path("nodeId").asLong(), "beat 块 nodeId 同步换新期节点");
        assertTrue(stored.get(1).path("npcId").isNull(), "门店分身不随期复制:必须置空");
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("章节故事流")), "置空必须有告警");
    }

    /** R14-VOICE:复刻中的是来源节点那条上游在途任务,新期没有对应 job,不得继承。 */
    @Test
    void reopenCarriesReadyVoiceAndDropsPendingVoice() {        NpcProfile pendingNpc = npc(501L, SOURCE_NODE);
        pendingNpc.setVoiceStatus(NodeNpcService.VOICE_PENDING);
        pendingNpc.setVoiceSample("/media/sample-501.wav");
        when(npcMapper.selectNodeNpcByNodeId(SOURCE_NODE)).thenReturn(pendingNpc);

        assertEquals(NEW_TOPIC, service.reopenNextEdition(SOURCE, "edition-r14-voice-pending"));

        assertNoVoiceCopied(insertedNpcs.get(0), "复刻中");
    }

    /** R14-VOICE:复刻失败是坏结果,复制过去只会把失败钉死在新期上;新 NPC 落 NONE 可重录。 */
    @Test
    void reopenDropsFailedVoice() {
        NpcProfile failedNpc = npc(501L, SOURCE_NODE);
        failedNpc.setVoiceStatus(NodeNpcService.VOICE_FAILED);
        failedNpc.setVoiceSample("/media/sample-501.wav");
        when(npcMapper.selectNodeNpcByNodeId(SOURCE_NODE)).thenReturn(failedNpc);

        assertEquals(NEW_TOPIC, service.reopenNextEdition(SOURCE, "edition-r14-voice-failed"));

        assertNoVoiceCopied(insertedNpcs.get(0), "复刻失败");
    }

    private void assertReadyVoiceCopied(NpcProfile copy) {
        assertEquals(READY_VOICE_ID, copy.getVoiceId(), "READY 音色必须随新期 NPC 复制");
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_READY), copy.getVoiceStatus());
        assertEquals(READY_VOICE_SAMPLE, copy.getVoiceSample(), "录音样本一并复制,音色丢了才重建得回来");
    }

    private void assertNoVoiceCopied(NpcProfile copy, String state) {
        assertNull(copy.getVoiceId(), state + "的 voiceId 不得复制");
        assertNull(copy.getVoiceSample(), state + "的录音样本不得复制");
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_NONE), copy.getVoiceStatus(),
                state + "的新期 NPC 必须落 NONE,不得继承在途/坏掉的状态");
    }

    private static NpcProfile npc(Long id, Long nodeId) {
        NpcProfile npc = new NpcProfile();
        npc.setId(id);
        npc.setScopeType(4);
        npc.setScopeId(nodeId);
        npc.setName("阿茉");
        npc.setAvatar("/media/npc.png");
        npc.setPersona("");
        npc.setGreeting("你好");
        npc.setSort(0);
        npc.setAuditStatus(1);
        npc.setEnabled(1);
        return npc;
    }
}
