package com.chengyinhub.business.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.NodeNpcService;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R13-NPC:复制含剧情与叙事 NPC 引用的主题 —— 副本必须引用副本的新 NPC id。
 *
 * <p>本用例刻意用「归属未变」的复制(极易被共享模板吞掉重映射):模板含 enter/outcome.npcId 时
 * 必须为新副本深拷贝一份模板再回写,否则改共享行会把来源期一起改掉。
 * 门店分身(npcdId=601)不随复制 -> 置空并告警,不得静默保留旧 id。
 */
class TopicCopyNpcRemapTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Long SOURCE_TOPIC = 41L;
    private static final Long NEW_TOPIC = 42L;
    private static final Long MEMBER = 20088L;
    private static final Long SOURCE_TEMPLATE = 7001L;
    private static final Long COPIED_TEMPLATE = 8001L;
    private static final String READY_VOICE_ID = "cosyvoice-v2-copy-ready";
    private static final String READY_VOICE_SAMPLE = "/media/sample-501.wav";
    private static final String TEMPLATE_JSON = "{\"schemaVersion\":1,"
            + "\"enter\":{\"npcId\":501,\"opener\":\"来了\"},"
            + "\"outcome\":{\"npcId\":999,\"line\":\"辛苦了\"}}";
    private static final String STORY = "{\"schemaVersion\":1,"
            + "\"call\":{\"npcId\":501,\"opener\":\"喂\"},"
            + "\"stages\":[{\"chapterId\":11}],"
            + "\"ending\":{\"epilogues\":["
            + "{\"npcId\":501,\"ally\":\"a\",\"neutral\":\"n\",\"guarded\":\"g\"},"
            + "{\"npcId\":502,\"ally\":\"a\",\"neutral\":\"n\",\"guarded\":\"g\"},"
            + "{\"npcId\":601,\"ally\":\"a\",\"neutral\":\"n\",\"guarded\":\"g\"}]}}";

    private final List<CmsTopicNode> insertedNodes = new ArrayList<CmsTopicNode>();
    private final List<NpcProfile> insertedNpcs = new ArrayList<NpcProfile>();
    private final List<CmsMemberTemplate> updatedTemplates = new ArrayList<CmsMemberTemplate>();
    private final List<CmsTopic> topicUpdates = new ArrayList<CmsTopic>();

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(com.chengyinhub.business.service.impl.TopicContentCopyServiceImpl.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<ILoggingEvent>();

    private CmsTopicMapper topicMapper;
    private CmsMemberTemplateMapper templateMapper;
    private NpcProfileMapper npcMapper;
    private CmsTopicServiceImpl service;

    @BeforeEach
    void setUp() {
        logAppender.start();
        serviceLogger.addAppender(logAppender);

        topicMapper = mock(CmsTopicMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        npcMapper = mock(NpcProfileMapper.class);

        service = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "cmsMemberTemplateMapper", templateMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "npcProfileMapper", npcMapper);
        ReflectionTestUtils.setField(service, "productRoleService", mock(IProductRoleService.class));
        ReflectionTestUtils.setField(service, "platformMemberId", MEMBER);

        CmsTopic source = new CmsTopic();
        source.setId(SOURCE_TOPIC);
        source.setMemberId(MEMBER);
        source.setProductType(ProductType.FREE_EXPLORE);
        source.setJourneyStoryJson(STORY);
        when(topicMapper.selectCmsTopicById(SOURCE_TOPIC)).thenReturn(source);
        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(NEW_TOPIC);
            return 1;
        }).when(topicMapper).insertCmsTopic(any(CmsTopic.class));
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenAnswer(invocation -> {
            topicUpdates.add(invocation.getArgument(0));
            return 1;
        });

        final List<CmsTopicChapter> copiedChapters = new ArrayList<CmsTopicChapter>();
        AtomicLong chapterIds = new AtomicLong(910L);
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenAnswer(invocation -> {
            CmsTopicChapter probe = invocation.getArgument(0, CmsTopicChapter.class);
            if (SOURCE_TOPIC.equals(probe.getTopicId())) {
                return new ArrayList<CmsTopicChapter>(Arrays.asList(
                        chapter(11L, 1L), chapter(12L, 2L)));
            }
            return new ArrayList<CmsTopicChapter>(copiedChapters);
        });
        doAnswer(invocation -> {
            CmsTopicChapter chapter = invocation.getArgument(0, CmsTopicChapter.class);
            chapter.setId(chapterIds.incrementAndGet());
            copiedChapters.add(chapter);
            return 1;
        }).when(chapterMapper).insertCmsTopicChapter(any(CmsTopicChapter.class));

        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(
                node(101L, 11L), node(102L, 12L)));
        AtomicLong nodeIds = new AtomicLong(1100L);
        doAnswer(invocation -> {
            CmsTopicNode inserted = invocation.getArgument(0, CmsTopicNode.class);
            inserted.setId(nodeIds.incrementAndGet());
            insertedNodes.add(inserted);
            return 1;
        }).when(nodeMapper).insertCmsTopicNode(any(CmsTopicNode.class));

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(SOURCE_TEMPLATE);
        template.setMemberId(MEMBER);
        template.setTitle("旧时钟");
        template.setStatus(1L);
        template.setDraftStatus(1);
        template.setIsSync(1);
        template.setAdvancedConfigJson(TEMPLATE_JSON);
        when(templateMapper.selectCmsMemberTemplateById(SOURCE_TEMPLATE)).thenReturn(template);
        doAnswer(invocation -> {
            ((CmsMemberTemplate) invocation.getArgument(0)).setId(COPIED_TEMPLATE);
            return 1;
        }).when(templateMapper).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
        when(templateMapper.updateCmsMemberTemplate(any(CmsMemberTemplate.class))).thenAnswer(invocation -> {
            updatedTemplates.add(invocation.getArgument(0));
            return 1;
        });

        NpcProfile readyNpc = npc(501L, 101L, "阿茉");
        readyNpc.setVoiceId(READY_VOICE_ID);
        readyNpc.setVoiceStatus(NodeNpcService.VOICE_READY);
        readyNpc.setVoiceSample(READY_VOICE_SAMPLE);
        when(npcMapper.selectNodeNpcByNodeId(101L)).thenReturn(readyNpc);
        when(npcMapper.selectNodeNpcByNodeId(102L)).thenReturn(npc(502L, 102L, "老周"));
        AtomicLong npcIds = new AtomicLong(9500L);
        doAnswer(invocation -> {
            NpcProfile inserted = invocation.getArgument(0, NpcProfile.class);
            inserted.setId(npcIds.incrementAndGet());
            insertedNpcs.add(inserted);
            return 1;
        }).when(npcMapper).insertNodeNpc(any(NpcProfile.class));
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    @Test
    void copyRemapsNpcRefsToCopiedNpcsAndNullsMerchantAvatar() throws Exception {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("memberId", MEMBER); // 归属未变:模板默认共享,含 NPC 引用时必须强制深拷贝

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, params);

        assertEquals(2, insertedNodes.size());
        for (CmsTopicNode node : insertedNodes) {
            assertEquals(COPIED_TEMPLATE, node.getTemplateId(),
                    "含 NPC 引用的模板必须为副本深拷贝,不能沿用共享的 " + SOURCE_TEMPLATE);
            assertNotEquals(SOURCE_TEMPLATE, node.getTemplateId());
        }

        assertEquals(2, insertedNpcs.size(), "节点 NPC 必须随节点复制");
        assertEquals(Long.valueOf(1101L), insertedNpcs.get(0).getScopeId());
        assertEquals(Long.valueOf(1102L), insertedNpcs.get(1).getScopeId());
        assertEquals(Integer.valueOf(4), insertedNpcs.get(0).getScopeType());
        assertEquals("阿茉", insertedNpcs.get(0).getName());
        assertEquals(Integer.valueOf(1), insertedNpcs.get(0).getEnabled(), "审核/启停态沿用来源");
        assertEquals(Long.valueOf(9501L), insertedNpcs.get(0).getId());

        ArgumentCaptor<CmsMemberTemplate> templatePatch = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(templateMapper).updateCmsMemberTemplate(templatePatch.capture());
        JsonNode block = JSON.readTree(templatePatch.getValue().getAdvancedConfigJson());
        assertEquals(9501L, block.path("enter").path("npcId").asLong(),
                "副本模板 enter.npcId 必须指向副本 NPC");
        assertTrue(block.path("outcome").path("npcId").isNull(),
                "映射不到的块配置 npcId 必须置空,不得保留旧 id");

        CmsTopic storyPatch = updatedTopicPatch();
        JsonNode story = JSON.readTree(storyPatch.getJourneyStoryJson());
        assertEquals(9501L, story.path("call").path("npcId").asLong());
        assertEquals(9501L, story.path("ending").path("epilogues").get(0).path("npcId").asLong());
        assertEquals(9502L, story.path("ending").path("epilogues").get(1).path("npcId").asLong());
        assertTrue(story.path("ending").path("epilogues").get(2).path("npcId").isNull(),
                "门店分身不随复制:epilogues 引用必须置空");

        assertTrue(anyWarn("=999"), "块配置映射不到必须有告警:" + warnings());
        assertTrue(anyWarn("=601"), "门店分身引用必须有告警:" + warnings());
        assertTrue(anyWarn("置空"), "告警要说清处置方式:" + warnings());

        assertReadyVoiceCopied(insertedNpcs.get(0));
    }

    /** R14-VOICE:复刻中的是来源节点那条上游在途任务,副本没有对应 job,不得继承。 */
    @Test
    void copyCarriesReadyVoiceAndDropsPendingVoice() throws Exception {
        NpcProfile pendingNpc = npc(502L, 102L, "老周");
        pendingNpc.setVoiceStatus(NodeNpcService.VOICE_PENDING);
        pendingNpc.setVoiceSample("/media/sample-502.wav");
        when(npcMapper.selectNodeNpcByNodeId(102L)).thenReturn(pendingNpc);

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, copyParams());

        assertReadyVoiceCopied(insertedNpcs.get(0));
        assertNoVoiceCopied(insertedNpcs.get(1), "复刻中");
    }

    /** R14-VOICE:复刻失败是坏结果,复制过去只会把失败钉死在副本上;新 NPC 落 NONE 可重录。 */
    @Test
    void copyDropsFailedVoice() throws Exception {
        NpcProfile failedNpc = npc(502L, 102L, "老周");
        failedNpc.setVoiceStatus(NodeNpcService.VOICE_FAILED);
        failedNpc.setVoiceSample("/media/sample-502.wav");
        when(npcMapper.selectNodeNpcByNodeId(102L)).thenReturn(failedNpc);

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, copyParams());

        assertReadyVoiceCopied(insertedNpcs.get(0));
        assertNoVoiceCopied(insertedNpcs.get(1), "复刻失败");
    }

    private Map<String, Object> copyParams() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("memberId", MEMBER);
        return params;
    }

    private void assertReadyVoiceCopied(NpcProfile copy) {
        assertEquals(READY_VOICE_ID, copy.getVoiceId(), "READY 音色必须随副本复制");
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_READY), copy.getVoiceStatus());
        assertEquals(READY_VOICE_SAMPLE, copy.getVoiceSample(), "录音样本一并复制,音色丢了才重建得回来");
    }

    private void assertNoVoiceCopied(NpcProfile copy, String state) {
        assertNull(copy.getVoiceId(), state + "的 voiceId 不得复制");
        assertNull(copy.getVoiceSample(), state + "的录音样本不得复制");
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_NONE), copy.getVoiceStatus(),
                state + "的副本必须落 NONE,不得继承在途/坏掉的状态");
    }

    private CmsTopic updatedTopicPatch() {
        for (CmsTopic patch : topicUpdates) {
            if (patch.getJourneyStoryJson() != null) {
                return patch;
            }
        }
        throw new AssertionError("副本剧情配置没有回填");
    }

    private boolean anyWarn(String fragment) {
        return logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains(fragment));
    }

    private String warnings() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "|" + right);
    }

    private static CmsTopicChapter chapter(Long id, Long sortId) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(id);
        chapter.setTopicId(SOURCE_TOPIC);
        chapter.setSortId(sortId);
        chapter.setRecruitEnabled(0);
        chapter.setMaxMerchant(0);
        return chapter;
    }

    private static CmsTopicNode node(Long id, Long chapterId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(SOURCE_TOPIC);
        node.setChapterId(chapterId);
        node.setName("节点" + id);
        node.setTemplateId(SOURCE_TEMPLATE);
        node.setStatus(1L);
        return node;
    }

    private static NpcProfile npc(Long id, Long nodeId, String name) {
        NpcProfile npc = new NpcProfile();
        npc.setId(id);
        npc.setScopeType(4);
        npc.setScopeId(nodeId);
        npc.setName(name);
        npc.setAvatar("/media/npc.png");
        npc.setPersona("");
        npc.setGreeting("你好");
        npc.setSort(0);
        npc.setAuditStatus(1);
        npc.setEnabled(1);
        return npc;
    }
    @Test void copySameOwnerTemplateWithOnlySysNodeConditionsGetsIndependentRemappedRow() throws Exception {
        CmsMemberTemplate source=templateMapper.selectCmsMemberTemplateById(SOURCE_TEMPLATE);
        String original="{\"schemaVersion\":1,\"requires\":[{\"var\":\"sys.passed.101\",\"op\":\"EQ\",\"value\":1}],\"interactionRules\":{\"when\":[{\"var\":\"sys.asked.102.question\",\"op\":\"EQ\",\"value\":1}]}}";
        source.setAdvancedConfigJson(original);com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC,copyParams());
        assertEquals(original,source.getAdvancedConfigJson());assertEquals(COPIED_TEMPLATE,insertedNodes.get(0).getTemplateId());assertEquals(1,updatedTemplates.size());
        JsonNode copied=JSON.readTree(updatedTemplates.get(0).getAdvancedConfigJson());assertEquals("sys.passed.1101",copied.path("requires").path(0).path("var").asText());assertEquals("sys.asked.1102.question",copied.path("interactionRules").path("when").path(0).path("var").asText());
    }

    @Test void copyGeneratedVoteToolRemapsItsActualPlanAndProvenanceIdentity() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode theme=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree("{\"schemaVersion\":1,\"gameplayRuntime\":{\"runtimeScope\":\"TEAM\",\"worldMode\":\"WORLD_SHARED\",\"acts\":[{\"actKey\":\"a\",\"questKeys\":[\"one\",\"two\"],\"completionRule\":{\"type\":\"ALL\"}}],\"quests\":[{\"questKey\":\"one\",\"actKey\":\"a\",\"nodeId\":101,\"locationKey\":\"one\",\"settlementSlot\":\"one\"},{\"questKey\":\"two\",\"actKey\":\"a\",\"nodeId\":102,\"locationKey\":\"two\",\"settlementSlot\":\"two\"}]}}");
        JsonNode config=JSON.readTree("{\"101\":{\"interactionTools\":{\"votePolicy\":\"MAJORITY\",\"plans\":[{\"title\":\"共同行动\",\"materialCost\":[]}]}}}");Map<Long,String> acts=new HashMap<>();acts.put(101L,"a");acts.put(102L,"a");CmsTopic original=topicMapper.selectCmsTopicById(SOURCE_TOPIC);original.setThemeRuntimeConfigJson(com.chengyinhub.business.service.support.GameplayTemplateRules.merge(theme,config,acts).toString());
        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC,copyParams());JsonNode copied=null;for(CmsTopic patch:topicUpdates)if(patch.getThemeRuntimeConfigJson()!=null)copied=JSON.readTree(patch.getThemeRuntimeConfigJson());assertTrue(copied!=null);assertEquals("tpl_vote_1101_1",copied.path("gameplayRuntime").path("plans").path(0).path("endingKey").asText());assertEquals(1101,copied.path("gameplayRuntime").path("plans").path(0).path("operationNodeId").asInt());assertEquals(1101,copied.path("gameplayRuntime").path("templateTools").path("nodes").path(0).path("nodeId").asInt());assertEquals("tpl_vote_1101_1",copied.path("gameplayRuntime").path("templateTools").path("nodes").path(0).path("planKeys").path(0).asText());
    }

}
