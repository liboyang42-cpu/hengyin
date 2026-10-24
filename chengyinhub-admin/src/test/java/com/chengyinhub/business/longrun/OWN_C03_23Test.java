package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN. Actual full-copy orchestration, stub persistence; no DB/reward-policy verification. */
class OWN_C03_23Test {
    private static final Long SOURCE = 100L;
    private static final Long COPY = 200L;
    private static final String RULE = "{\"nodeCompletion\":{\"mode\":\"AT_LEAST\",\"requiredCount\":1},\"future\":{\"kept\":true}}";
    private static final String STORY = "{\"schemaVersion\":1,\"stages\":[{\"chapterId\":10}],\"ending\":{\"dispositionNodeId\":11,\"endings\":[{\"code\":\"first\",\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":12},{\"var\":\"sys.asked.11.q_1\"},{\"var\":\"sys.outcome.12\"}]},{\"code\":\"last\",\"fallback\":true}]},\"future\":{\"nodeId\":11}}";
    private CmsTopicServiceImpl service;
    private CmsTopicMapper topics;
    private CmsTopicNodeMapper nodes;
    private final List<CmsTopicNode> copiedNodes = new ArrayList<CmsTopicNode>();

    @BeforeEach void setup() throws Exception {
        service = new CmsTopicServiceImpl();
        for (Field field : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            field.setAccessible(true);
            if (field.get(service) == null && field.isAnnotationPresent(Autowired.class)) {
                ReflectionTestUtils.setField(service, field.getName(), mock(field.getType()));
            }
        }
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wireRelocatedCollaborators(service, publicationField -> org.mockito.Mockito.mock(publicationField.getType()));
        topics = (CmsTopicMapper) ReflectionTestUtils.getField(service, "cmsTopicMapper");
        CmsTopicChapterMapper chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
        nodes = (CmsTopicNodeMapper) ReflectionTestUtils.getField(service, "cmsTopicNodeMapper");
        CmsTopic original = new CmsTopic();
        original.setId(SOURCE);
        original.setMemberId(42L);
        original.setName("同作者原主题");
        original.setProductType(ProductType.ORIENTEERING);
        original.setDelFlag(0);
        original.setJourneyStoryJson(STORY);
        when(topics.selectCmsTopicById(SOURCE)).thenReturn(original);
        doAnswer(call -> { ((CmsTopic) call.getArgument(0)).setId(COPY); return 1; })
                .when(topics).insertCmsTopic(any(CmsTopic.class));
        when(topics.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(10L);
        chapter.setTopicId(SOURCE);
        chapter.setName("原章");
        chapter.setRecruitEnabled(0);
        chapter.setMaxMerchant(4);
        when(chapters.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenReturn(Collections.singletonList(chapter));
        doAnswer(call -> { ((CmsTopicChapter) call.getArgument(0)).setId(210L); return 1; })
                .when(chapters).insertCmsTopicChapter(any(CmsTopicChapter.class));
        List<CmsTopicNode> originalNodes = Arrays.asList(stored(11L), stored(12L));
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenAnswer(call -> {
            CmsTopicNode query = call.getArgument(0);
            return SOURCE.equals(query.getTopicId()) ? originalNodes : copiedNodes;
        });
        AtomicLong nodeIds = new AtomicLong(300L);
        doAnswer(call -> {
            CmsTopicNode copy = call.getArgument(0);
            copy.setId(nodeIds.incrementAndGet());
            copiedNodes.add(copy);
            return 1;
        }).when(nodes).insertCmsTopicNode(any(CmsTopicNode.class));
    }

    @Test void fullCopyRewritesPlainStoryToActualCopiedNodes() throws Exception {
        assertEquals(COPY, copy(2));
        ArgumentCaptor<CmsTopic> patch = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topics).updateCmsTopic(patch.capture());
        com.fasterxml.jackson.databind.JsonNode result = new ObjectMapper().readTree(patch.getValue().getJourneyStoryJson());
        assertEquals(210L, result.path("stages").get(0).path("chapterId").asLong());
        assertEquals(301L, result.path("ending").path("dispositionNodeId").asLong());
        com.fasterxml.jackson.databind.JsonNode endings = result.path("ending").path("endings");
        assertEquals("first", endings.get(0).path("code").asText());
        assertEquals("last", endings.get(1).path("code").asText());
        assertEquals(302L, endings.get(0).path("when").get(0).path("nodeId").asLong());
        assertEquals("sys.asked.301.q_1", endings.get(0).path("when").get(1).path("var").asText());
        assertEquals("sys.outcome.302", endings.get(0).path("when").get(2).path("var").asText());
        assertEquals(11L, result.path("future").path("nodeId").asLong());
    }
    @Test void missingReferencedNodeCannotWriteCopiedStory() {
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Collections.singletonList(stored(11L)));
        assertThrows(IllegalArgumentException.class, () -> copy(2));
        verify(topics, never()).updateCmsTopic(any());
    }
    @Test void basicCopyDoesNotStartFullContentRemapping() {
        assertEquals(COPY, copy(1));
        verify(nodes, never()).insertCmsTopicNode(any());
        verify(topics, never()).updateCmsTopic(any());
    }
    private Long copy(int type) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("memberId", 42L); params.put("copyType", type);
        return com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE, params);
    }
    private CmsTopicNode stored(Long id) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id); node.setTopicId(SOURCE); node.setChapterId(10L); node.setName("节点" + id);
        return node;
    }
}
