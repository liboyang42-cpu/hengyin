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
class OWN_C03_25Test {
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
    private void newEndings() throws Exception {
        nodeMap().clear(); nodeMap().put(22L, 222L);
        @SuppressWarnings("unchecked") List<com.chengyinhub.business.service.support.TopicAuthoredGraphIds.EndingChapterSnapshot> endings =
                (List<com.chengyinhub.business.service.support.TopicAuthoredGraphIds.EndingChapterSnapshot>) ReflectionTestUtils.getField(maps, "endingChapters");
        endings.add(new com.chengyinhub.business.service.support.TopicAuthoredGraphIds.EndingChapterSnapshot(820L, "新结局", null,
                json.readTree("{\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":22}]}")));
        endings.add(new com.chengyinhub.business.service.support.TopicAuthoredGraphIds.EndingChapterSnapshot(821L, "其他", null, json.readTree("{\"fallback\":true}")));
    }
    private String oldStory() {
        return "{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"old\",\"title\":\"旧结局\",\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":11}]},{\"code\":\"fallback\",\"title\":\"其他\",\"fallback\":true}]},\"future\":{\"nodeId\":11}}";
    }
    @Test void replacedOldEndingsDoNotRequireDeletedNodeMapping() throws Exception {
        newEndings();
        JsonNode result = save(oldStory());
        JsonNode endings = result.path("ending").path("endings");
        assertEquals("ending1", endings.get(0).path("code").asText());
        assertEquals(222L, endings.get(0).path("when").get(0).path("nodeId").asLong());
        assertEquals(820L, endings.get(0).path("chapterId").asLong());
        assertTrue(endings.get(1).path("fallback").asBoolean());
        assertEquals(11L, result.path("future").path("nodeId").asLong());
    }
    @Test void retainedDispositionStillRequiresMapping() throws Exception {
        newEndings();
        com.fasterxml.jackson.databind.node.ObjectNode raw = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(oldStory());
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw.path("ending")).put("dispositionNodeId", 11L);
        assertThrows(IllegalArgumentException.class, () -> save(raw.toString()));
        verifyNoInteractions(topics);
    }
    @Test void retainedStageStillRequiresMapping() throws Exception {
        newEndings();
        com.fasterxml.jackson.databind.node.ObjectNode raw = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(oldStory());
        raw.set("stages", json.readTree("[{\"chapterId\":10,\"end\":{\"changed\":[{\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":11}}]}}]"));
        assertThrows(IllegalArgumentException.class, () -> save(raw.toString()));
        verifyNoInteractions(topics);
    }
    @Test void retainedExhibitStillRequiresMapping() throws Exception {
        newEndings();
        com.fasterxml.jackson.databind.node.ObjectNode raw = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(oldStory());
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw.path("ending")).set("exhibits", json.readTree("[{\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":11}}]"));
        assertThrows(IllegalArgumentException.class, () -> save(raw.toString()));
        verifyNoInteractions(topics);
    }
    @Test void noReplacementKeepsOldEndingMappingStrict() {
        nodeMap().clear(); nodeMap().put(22L, 222L);
        assertThrows(IllegalArgumentException.class, () -> save(oldStory()));
        verifyNoInteractions(topics);
    }
    @Test void unknownSchemaVersionStillRejectedByOriginalValidator() throws Exception {
        newEndings();
        assertThrows(IllegalArgumentException.class, () -> save(oldStory().replace("schemaVersion\":1", "schemaVersion\":99")));
        verifyNoInteractions(topics);
    }
}
