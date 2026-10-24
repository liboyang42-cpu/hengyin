package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.NodeDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TopicAuthoredGraphIdsTest {
    @Test void snapshotKeepsInsertionOrderAndCannotBeMutatedThroughInputsOrOutputs() {
        Map<String,Long> clients=new LinkedHashMap<>();clients.put("second",902L);clients.put("first",901L);
        Map<Long,Long> old=new LinkedHashMap<>();old.put(12L,902L);old.put(11L,901L);
        Set<Long> nodes=new LinkedHashSet<>(List.of(902L,901L));Map<Long,Long> chapters=new LinkedHashMap<>();chapters.put(10L,810L);
        TopicAuthoredGraphIds ids=new TopicAuthoredGraphIds(null,List.of(),clients,Map.of(),old,nodes,chapters);
        clients.clear();old.clear();nodes.clear();chapters.clear();
        assertEquals(List.of("second","first"),new ArrayList<>(ids.clientToNew.keySet()));
        assertEquals(List.of(902L,901L),new ArrayList<>(ids.newNodeIds));assertEquals(810L,ids.chapterOldToNew.get(10L));
        assertThrows(UnsupportedOperationException.class,()->ids.clientToNew.clear());
        assertThrows(UnsupportedOperationException.class,()->ids.createdClientToNew.put("new",903L));
        assertThrows(UnsupportedOperationException.class,()->ids.oldToNew.clear());
        assertThrows(UnsupportedOperationException.class,()->ids.chapterOldToNew.clear());
        assertThrows(UnsupportedOperationException.class,()->ids.newNodeIds.clear());
    }

    @Test void endingJsonCannotMutateTheStoredSnapshot() throws Exception {
        ObjectNode ending=(ObjectNode)new ObjectMapper().readTree("{\"fallback\":true,\"future\":{\"kept\":true}}");
        var row=new TopicAuthoredGraphIds.EndingChapterSnapshot(820L,"ending",null,ending);
        List<TopicAuthoredGraphIds.EndingChapterSnapshot> input=new ArrayList<>(List.of(row));
        TopicAuthoredGraphIds ids=new TopicAuthoredGraphIds(null,input,Map.of(),Map.of(),Map.of(),Set.of(),Map.of());
        ending.put("fallback",false);input.clear();
        ObjectNode first=(ObjectNode)ReflectionTestUtils.getField(ids.endingChapters().get(0),"ending");
        assertTrue(first.path("fallback").asBoolean());first.put("fallback",false);
        ObjectNode second=(ObjectNode)ReflectionTestUtils.getField(ids.endingChapters().get(0),"ending");
        assertTrue(second.path("fallback").asBoolean());assertTrue(second.path("future").path("kept").asBoolean());
        assertThrows(UnsupportedOperationException.class,()->ids.endingChapters().clear());
    }

    @Test void productionBuilderRetainsDuplicateIdentityChecksAndFreezesIndependently() throws Exception {
        Class<?> type=Class.forName("com.chengyinhub.business.service.impl.TopicContentGraphAuthoringServiceImpl$Builder");
        var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object builder=constructor.newInstance();
        NodeDTO first=new NodeDTO();first.setId(11L);first.setClientNodeKey("first");
        ReflectionTestUtils.invokeMethod(builder,"record",first,901L);
        TopicAuthoredGraphIds frozen=ReflectionTestUtils.invokeMethod(builder,"freeze");
        NodeDTO second=new NodeDTO();second.setId(12L);second.setClientNodeKey("first");
        assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(builder,"record",second,902L));
        assertEquals(Map.of(11L,901L),frozen.oldToNew);assertEquals(Map.of("first",901L),frozen.clientToNew);
    }
}
