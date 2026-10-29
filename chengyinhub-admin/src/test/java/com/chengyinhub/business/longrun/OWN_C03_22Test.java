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
class OWN_C03_22Test {
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
                com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, field.getName(), mock(field.getType()));
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

    @Test void topicInsertMustSucceedAndGeneratePositiveIdentityBeforeChildren() throws Exception {
        for (int failure = 0; failure < 4; failure++) {
            setup();
            final int mode = failure;
            doAnswer(call -> {
                CmsTopic row = call.getArgument(0);
                row.setId(mode == 0 ? COPY : mode == 1 ? null : mode == 2 ? 0L : -1L);
                return mode == 0 ? 0 : 1;
            }).when(topics).insertCmsTopic(any(CmsTopic.class));
            assertThrows(IllegalStateException.class, () -> copy());
            CmsTopicChapterMapper chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
            verify(chapters, never()).selectCmsTopicChapterList(any());
            verifyNoInteractions(ReflectionTestUtils.getField(service, "productRoleService"));
        }
    }
    @Test void chapterInsertMustSucceedAndGenerateIdentityBeforeNodes() throws Exception {
        for (int failure = 0; failure < 4; failure++) {
            setup(); final int mode = failure;
            CmsTopicChapterMapper chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
            doAnswer(call -> {
                CmsTopicChapter row = call.getArgument(0);
                row.setId(mode == 1 ? null : Long.valueOf(mode == 0 ? 210L : mode == 2 ? 0L : -1L));
                return mode == 0 ? 0 : 1;
            }).when(chapters).insertCmsTopicChapter(any(CmsTopicChapter.class));
            assertThrows(IllegalStateException.class, () -> copy());
            verify(nodes, never()).insertCmsTopicNode(any());
        }
    }
    @Test void nodeInsertMustSucceedAndGenerateIdentityBeforeNpcOrRuleWrite() throws Exception {
        for (int failure = 0; failure < 4; failure++) {
            setup(); final int mode = failure;
            doAnswer(call -> {
                CmsTopicNode row = call.getArgument(0);
                row.setId(mode == 1 ? null : Long.valueOf(mode == 0 ? 310L : mode == 2 ? 0L : -1L));
                return mode == 0 ? 0 : 1;
            }).when(nodes).insertCmsTopicNode(any(CmsTopicNode.class));
            assertThrows(IllegalStateException.class, () -> copy());
            verify(topics, never()).updateCmsTopic(any());
            verifyNoInteractions(com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(service, "npcProfileMapper"));
        }
    }
    @Test void successfulCopyStillReturnsGeneratedIdentity() {
        assertEquals(COPY, copy());
    }
    private Long copy() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("memberId", 42L); params.put("copyType", 2);
        return com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE, params);
    }
    private CmsTopicNode stored(Long id) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id); node.setTopicId(SOURCE); node.setChapterId(10L); node.setName("节点" + id);
        return node;
    }
}
