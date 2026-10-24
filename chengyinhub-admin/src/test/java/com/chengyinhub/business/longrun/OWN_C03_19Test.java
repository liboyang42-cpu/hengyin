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
class OWN_C03_19Test {
    private static final Long SOURCE = 100L;
    private static final Long COPY = 200L;
    private static final String RULE = "{\"nodeCompletion\":{\"mode\":\"AT_LEAST\",\"requiredCount\":1},\"future\":{\"kept\":true}}";
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
        original.setCompleteRuleJson(RULE);
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

    @Test void sameOwnerFullCopyPreservesCountRuleAndUnknownMetadata() throws Exception {
        assertEquals(COPY, copy(42L, 2));
        ArgumentCaptor<CmsTopic> patch = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topics).updateCmsTopic(patch.capture());
        assertEquals(COPY, patch.getValue().getId());
        assertEquals(new ObjectMapper().readTree(RULE), new ObjectMapper().readTree(patch.getValue().getCompleteRuleJson()));
        assertEquals(2, copiedNodes.size());
        assertNotEquals(Long.valueOf(11), copiedNodes.get(0).getId());
    }
    @Test void crossOwnerFullCopyKeepsExistingRewardBoundary() {
        assertEquals(COPY, copy(84L, 2));
        assertNoCompletionRuleWrite();
    }
    @Test void basicCopyDoesNotCopyOldNodeThresholds() {
        assertEquals(COPY, copy(42L, 1));
        assertNoCompletionRuleWrite();
        verify(nodes, never()).insertCmsTopicNode(any());
    }
    @Test void incompleteNodeCopyCannotReportPreservedRule() {
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenAnswer(call -> {
            CmsTopicNode query = call.getArgument(0);
            return SOURCE.equals(query.getTopicId()) ? Arrays.asList(stored(11L), stored(12L)) : Collections.emptyList();
        });
        assertThrows(IllegalStateException.class, () -> copy(42L, 2));
        verify(topics, never()).updateCmsTopic(any());
    }
    @Test void failedCompletionRuleWriteIsNotSuccessfulCopy() {
        when(topics.updateCmsTopic(any(CmsTopic.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> copy(42L, 2));
    }
    private Long copy(Long member, int type) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("memberId", member);
        params.put("copyType", type);
        return com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE, params);
    }
    private CmsTopicNode stored(Long id) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(SOURCE);
        node.setChapterId(10L);
        node.setName("节点" + id);
        return node;
    }
    private void assertNoCompletionRuleWrite() {
        ArgumentCaptor<CmsTopic> inserted = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topics).insertCmsTopic(inserted.capture());
        assertNull(inserted.getValue().getCompleteRuleJson());
        verify(topics, never()).updateCmsTopic(any());
    }
}
