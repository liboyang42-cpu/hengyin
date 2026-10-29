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
class OWN_C03_20Test {
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
        original.setJourneyStoryJson("{\"schemaVersion\":1,\"stages\":[{\"chapterId\":10},{\"chapterId\":20}],\"future\":{\"kept\":true}}");
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
        chapter.setSortId(1L);
        CmsTopicChapter other = new CmsTopicChapter();
        other.setId(20L); other.setTopicId(SOURCE); other.setName("第二章");
        other.setSortId(1L); other.setRecruitEnabled(0); other.setMaxMerchant(4);
        // Legal tied sort values: later queries may return a different order.
        when(chapters.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Arrays.asList(chapter, other), Arrays.asList(other, chapter));
        doAnswer(call -> {
            CmsTopicChapter inserted = call.getArgument(0);
            inserted.setId("原章".equals(inserted.getName()) ? 210L : 220L);
            return 1;
        }).when(chapters).insertCmsTopicChapter(any(CmsTopicChapter.class));
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Collections.emptyList());

    }


    @Test void fullCopyUsesGeneratedIdentityDespiteTiedSortOrder() throws Exception {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("memberId", 42L); params.put("copyType", 2);
        assertEquals(COPY, com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE, params));
        ArgumentCaptor<CmsTopic> patch = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topics).updateCmsTopic(patch.capture());
        com.fasterxml.jackson.databind.JsonNode story = new ObjectMapper().readTree(patch.getValue().getJourneyStoryJson());
        assertEquals(210L, story.path("stages").get(0).path("chapterId").asLong());
        assertEquals(220L, story.path("stages").get(1).path("chapterId").asLong());
        assertTrue(story.path("future").path("kept").asBoolean());
        CmsTopicChapterMapper chapters = (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper");
        verify(chapters, times(1)).selectCmsTopicChapterList(any(CmsTopicChapter.class));
    }
}
