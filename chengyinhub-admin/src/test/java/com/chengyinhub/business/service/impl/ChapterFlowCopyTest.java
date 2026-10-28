package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 已物化主题复制必须保留完整剧本，并把物理 nodeId 绑定到副本节点。 */
class ChapterFlowCopyTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void materializedBlocksAreCopiedWithNewNodeIds() throws Exception
    {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "sysCategoryService", mock(ISysCategoryService.class));

        CmsTopic sourceTopic = new CmsTopic();
        sourceTopic.setId(41L);
        sourceTopic.setDelFlag(0);
        sourceTopic.setProductType(ProductType.ORIENTEERING);
        when(topicMapper.selectCmsTopicById(41L)).thenReturn(sourceTopic);
        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(42L);
            return 1;
        }).when(topicMapper).insertCmsTopic(any());

        CmsTopicChapter sourceChapter = new CmsTopicChapter();
        sourceChapter.setId(6L);
        sourceChapter.setTopicId(41L);
        sourceChapter.setRequired(1);
        sourceChapter.setRecruitEnabled(0);
        sourceChapter.setMaxMerchant(0);
        sourceChapter.setDescription("开场");
        sourceChapter.setBlocksJson("[{\"type\":\"text\",\"content\":\"开场\"},"
                + "{\"type\":\"node\",\"nodeId\":11},"
                + "{\"type\":\"text\",\"content\":\"首节点后的完整剧本\"}]");
        when(chapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(sourceChapter));
        doAnswer(invocation -> {
            ((CmsTopicChapter) invocation.getArgument(0)).setId(60L);
            return 1;
        }).when(chapterMapper).insertCmsTopicChapter(any());
        when(chapterMapper.updateCmsTopicChapter(any())).thenReturn(1);

        CmsTopicNode sourceNode = new CmsTopicNode();
        sourceNode.setId(11L);
        sourceNode.setTopicId(41L);
        sourceNode.setChapterId(6L);
        sourceNode.setSortId(1L);
        when(nodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(sourceNode));
        doAnswer(invocation -> {
            ((CmsTopicNode) invocation.getArgument(0)).setId(110L);
            return 1;
        }).when(nodeMapper).insertCmsTopicNode(any());

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(41L, Collections.<String, Object>singletonMap("copyType", 2));

        ArgumentCaptor<CmsTopicChapter> copied = ArgumentCaptor.forClass(CmsTopicChapter.class);
        verify(chapterMapper).updateCmsTopicChapter(copied.capture());
        assertNotNull(copied.getValue().getBlocksJson(),
                "已物化章节复制不得把 blocks 静默变回 null");
        List<Map<String, Object>> blocks = JSON.readValue(copied.getValue().getBlocksJson(),
                new TypeReference<List<Map<String, Object>>>() { });
        assertEquals(3, blocks.size(), "复制不得把首个 node 后的 text 丢掉");
        assertEquals("首节点后的完整剧本", blocks.get(2).get("content"));
        assertEquals(110L, ((Number) blocks.get(1).get("nodeId")).longValue(),
                "副本 blocks 必须引用副本新节点 id");
        assertFalse(Long.valueOf(11L).equals(((Number) blocks.get(1).get("nodeId")).longValue()),
                "副本不得继续跨主题引用源节点 id");
    }

    /**
     * R19:叙事搬进章节故事流后,副本 beat 块上的 npcId 必须换成副本 NPC,
     * nodeId 同时换成副本节点 —— 漏了 npcId,副本会静默用来源主题的 NPC。
     *
     * <p>负控:删掉 copyChaptersAndNodes 里的 remapChapterBlockNpcIds 调用,本用例立刻红。
     */
    @Test
    void beatBlockNpcIdIsRemappedToCopiedNpc() throws Exception
    {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        NpcProfileMapper npcMapper = mock(NpcProfileMapper.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "npcProfileMapper", npcMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "sysCategoryService", mock(ISysCategoryService.class));

        CmsTopic sourceTopic = new CmsTopic();
        sourceTopic.setId(41L);
        sourceTopic.setDelFlag(0);
        sourceTopic.setProductType(ProductType.ORIENTEERING);
        when(topicMapper.selectCmsTopicById(41L)).thenReturn(sourceTopic);
        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(42L);
            return 1;
        }).when(topicMapper).insertCmsTopic(any());

        CmsTopicChapter sourceChapter = new CmsTopicChapter();
        sourceChapter.setId(6L);
        sourceChapter.setTopicId(41L);
        sourceChapter.setRequired(1);
        sourceChapter.setRecruitEnabled(0);
        sourceChapter.setMaxMerchant(0);
        sourceChapter.setDescription("开场");
        sourceChapter.setBlocksJson("[{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\","
                + "\"nodeId\":11,\"npcId\":501,\"content\":\"来了\"},"
                + "{\"type\":\"node\",\"nodeId\":11}]");
        when(chapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(sourceChapter));
        doAnswer(invocation -> {
            ((CmsTopicChapter) invocation.getArgument(0)).setId(60L);
            return 1;
        }).when(chapterMapper).insertCmsTopicChapter(any());
        when(chapterMapper.updateCmsTopicChapter(any())).thenReturn(1);

        CmsTopicNode sourceNode = new CmsTopicNode();
        sourceNode.setId(11L);
        sourceNode.setTopicId(41L);
        sourceNode.setChapterId(6L);
        sourceNode.setSortId(1L);
        when(nodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(sourceNode));
        doAnswer(invocation -> {
            ((CmsTopicNode) invocation.getArgument(0)).setId(110L);
            return 1;
        }).when(nodeMapper).insertCmsTopicNode(any());

        NpcProfile sourceNpc = new NpcProfile();
        sourceNpc.setId(501L);
        sourceNpc.setScopeType(4);
        sourceNpc.setScopeId(11L);
        sourceNpc.setName("阿荣");
        sourceNpc.setAvatar("/media/npc.png");
        sourceNpc.setPersona("");
        sourceNpc.setGreeting("你好");
        sourceNpc.setSort(0);
        sourceNpc.setAuditStatus(1);
        sourceNpc.setEnabled(1);
        when(npcMapper.selectNodeNpcByNodeId(11L)).thenReturn(sourceNpc);
        doAnswer(invocation -> {
            ((NpcProfile) invocation.getArgument(0)).setId(9501L);
            return 1;
        }).when(npcMapper).insertNodeNpc(any());

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(41L, Collections.<String, Object>singletonMap("copyType", 2));

        ArgumentCaptor<CmsTopicChapter> copied = ArgumentCaptor.forClass(CmsTopicChapter.class);
        verify(chapterMapper).updateCmsTopicChapter(copied.capture());
        List<Map<String, Object>> blocks = JSON.readValue(copied.getValue().getBlocksJson(),
                new TypeReference<List<Map<String, Object>>>() { });
        assertEquals(110L, ((Number) blocks.get(0).get("nodeId")).longValue(),
                "beat 块的 nodeId 必须换成副本节点");
        assertEquals(9501L, ((Number) blocks.get(0).get("npcId")).longValue(),
                "beat 块的 npcId 必须换成副本 NPC,不得继续指向来源主题");
    }
}
