package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyStory;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Constructor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN. Real service persistence consumer plus real producer helper, stub mapper; no DB proof. */
class OWN_C03_05Test {
    private final ObjectMapper json = new ObjectMapper();
    private CmsTopicServiceImpl service;
    private CmsTopicMapper topics;
    private Object maps;
    @BeforeEach void setup() throws Exception {
        service = new CmsTopicServiceImpl(); topics = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        when(topics.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        Class<?> type = Class.forName("com.chengyinhub.business.service.impl.TopicContentGraphAuthoringServiceImpl$Builder");
        Constructor<?> ctor = type.getDeclaredConstructor(); ctor.setAccessible(true); maps = ctor.newInstance();
        nodeMap().put(11L, 901L); chapterMap().put(10L, 810L);
    }
    @SuppressWarnings("unchecked") private Map<Long, Long> nodeMap() {
        return (Map<Long, Long>) ReflectionTestUtils.getField(maps, "oldToNew");
    }
    @SuppressWarnings("unchecked") private Map<Long, Long> chapterMap() {
        return (Map<Long, Long>) ReflectionTestUtils.getField(maps, "chapterOldToNew");
    }
    private JsonNode save(String raw) throws Exception {
        ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.graph(service), "persistJourneyStoryAfterChapterRebuild", 700L, raw, ReflectionTestUtils.invokeMethod(maps, "freeze"), "creator");
        ArgumentCaptor<CmsTopic> patch = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topics).updateCmsTopic(patch.capture());
        return json.readTree(patch.getValue().getJourneyStoryJson());
    }
    @Test void existingPlainStoryReferencesAndUnknownMetadataSurviveRebuild() throws Exception {
        JsonNode result = save("{\"schemaVersion\":1,\"stages\":[{\"chapterId\":10,\"end\":{\"changed\":[{\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":11}}]}}],\"ending\":{\"dispositionNodeId\":11,\"endings\":[{\"code\":\"first\",\"when\":[{\"var\":\"sys.asked.11.question_1\"},{\"var\":\"sys.outcome.11\"},{\"var\":\"sys.mistakes.11\"},{\"var\":\"sys.passed.11\"}]},{\"code\":\"last\",\"fallback\":true}]},\"future\":{\"nodeId\":11}}");
        assertEquals(810L, result.path("stages").get(0).path("chapterId").asLong());
        assertEquals(901L, result.path("stages").get(0).path("end").path("changed").get(0).path("when").path("nodeId").asLong());
        assertEquals(901L, result.path("ending").path("dispositionNodeId").asLong());
        JsonNode endings = result.path("ending").path("endings");
        assertEquals("first", endings.get(0).path("code").asText());
        assertEquals("last", endings.get(1).path("code").asText());
        JsonNode conditions = endings.get(0).path("when");
        assertEquals("sys.asked.901.question_1", conditions.get(0).path("var").asText());
        assertEquals("sys.outcome.901", conditions.get(1).path("var").asText());
        assertEquals("sys.mistakes.901", conditions.get(2).path("var").asText());
        assertEquals("sys.passed.901", conditions.get(3).path("var").asText());
        assertEquals(11L, result.path("future").path("nodeId").asLong());
    }
    @Test void missingOldNodeMappingFailsBeforeStoryWrite() {
        nodeMap().clear();
        assertThrows(IllegalArgumentException.class, () -> save("{\"ending\":{\"dispositionNodeId\":11}}"));
        verifyNoInteractions(topics);
    }
    @Test void generatedEndingConditionsAreMappedOnceAfterStoredStory() throws Exception {
        @SuppressWarnings("unchecked") List<com.chengyinhub.business.service.support.TopicAuthoredGraphIds.EndingChapterSnapshot> endings =
                (List<com.chengyinhub.business.service.support.TopicAuthoredGraphIds.EndingChapterSnapshot>) ReflectionTestUtils.getField(maps, "endingChapters");
        endings.add(new com.chengyinhub.business.service.support.TopicAuthoredGraphIds.EndingChapterSnapshot(820L, "到过", null,
                json.readTree("{\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":11}]}")));
        endings.add(new com.chengyinhub.business.service.support.TopicAuthoredGraphIds.EndingChapterSnapshot(821L, "其他", null, json.readTree("{\"fallback\":true}")));
        JsonNode result = save("{\"schemaVersion\":1}");
        assertEquals(901L, result.path("ending").path("endings").get(0).path("when").get(0).path("nodeId").asLong());
        assertEquals(820L, result.path("ending").path("endings").get(0).path("chapterId").asLong());
    }
}
