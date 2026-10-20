package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.LegacyCmsTemplateBodyReader;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runtime routing policy tests, not datasource/locking acceptance (reader has separate real JDBC tests). */
class TopicRouteRuntimeProvenanceTest {
    private TopicRouteRuntimeServiceImpl runtime;
    private final LegacyCmsTemplateBodyReader reader=mock(LegacyCmsTemplateBodyReader.class);
    private final CmsMemberTemplateMapper raw=mock(CmsMemberTemplateMapper.class);
    @BeforeEach void setup(){
        runtime=new TopicRouteRuntimeServiceImpl(mock(TopicRouteSessionMapper.class),mock(CmsTopicMapper.class),
                mock(CmsTopicNodeMapper.class),raw,mock(PlayerNodeProgressMapper.class),mock(CmsActivityMapper.class),
                mock(CmsRegistrationMapper.class),null,()->1L,java.time.Clock.systemUTC(),
                com.chengyinhub.business.service.support.RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(runtime,"legacyTemplateBodyReader",reader);
    }
    private CmsTopicNode node(long template){CmsTopicNode n=new CmsTopicNode();n.setId(1L);n.setTemplateId(template);return n;}
    private CmsMemberTemplate body(long id){CmsMemberTemplate t=new CmsMemberTemplate();t.setId(id);t.setAdvancedConfigJson("{\"effects\":[]}");return t;}
    private JsonNode config(String snapshot){TopicRouteSession s=new TopicRouteSession();s.setConfigSnapshotJson(snapshot);return ReflectionTestUtils.invokeMethod(runtime,"nodeBlockConfig",s,node(10));}
    @Test void newFreezeInputLocksDistinctIdsInAscendingOrder(){
        when(reader.findLegacy(10L)).thenReturn(body(10));when(reader.findLegacy(20L)).thenReturn(body(20));
        Map<Long,CmsMemberTemplate> found=ReflectionTestUtils.invokeMethod(runtime,"templateEntities",Arrays.asList(node(20),node(10),node(20)));
        assertEquals(Arrays.asList(10L,20L),new ArrayList<>(found.keySet()));
        InOrder order=inOrder(reader);order.verify(reader).findLegacy(10L);order.verify(reader).findLegacy(20L);verifyNoMoreInteractions(reader);verifyNoInteractions(raw);
    }
    @Test void deniedOrMissingFreezeInputCannotBeSilentlyOmitted(){
        assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(runtime,"templateEntities",Collections.singletonList(node(10))));verifyNoInteractions(raw);
    }
    @Test void completeFrozenBlockIncludingEmptyObjectNeverReadsLive(){
        assertEquals(7,config("{\"nodeJourney\":{\"1\":{\"frozen\":7}}}").get("frozen").intValue());
        assertTrue(config("{\"nodeJourney\":{\"1\":{}}}").isObject());verifyNoInteractions(reader,raw);
    }
    @Test void historicalAbsentSnapshotOrAbsentKeyUsesOnlyProtectedReader(){
        when(reader.findLegacy(10L)).thenReturn(body(10));
        for(String old:new String[]{null,"","  ","{}","{\"legacy\":true}"})assertTrue(config(old).has("effects"));
        verify(reader,times(5)).findLegacy(10L);verifyNoInteractions(raw);
    }
    @Test void damagedSnapshotAndPresentButInvalidOrMissingBlockNeverFallback(){
        for(String bad:new String[]{"{", "{} {}", "{} trailing", "null","[]","{\"nodeJourney\":null}","{\"nodeJourney\":[]}","{\"nodeJourney\":{}}","{\"nodeJourney\":{\"1\":null}}","{\"nodeJourney\":{\"1\":[]}}"})
            assertThrows(IllegalStateException.class,()->config(bad),bad);
        verifyNoInteractions(reader,raw);
    }
    @Test void blockedHistoricalFallbackThrowsWithoutRawRead(){assertThrows(ServiceException.class,()->config("{}"));verifyNoInteractions(raw);}
    @Test void databaseFailurePropagatesFromLiveReader(){
        DataAccessResourceFailureException failure=new DataAccessResourceFailureException("unavailable");when(reader.findLegacy(10L)).thenThrow(failure);
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->config("{}")));verifyNoInteractions(raw);
    }
    @Test void missingRequiredReaderFailsOnlyWhenLiveBodyIsNeeded(){
        ReflectionTestUtils.setField(runtime,"legacyTemplateBodyReader",null);
        assertThrows(IllegalStateException.class,()->config("{}"));assertNotNull(config("{\"nodeJourney\":{\"1\":{}}}"));verifyNoInteractions(raw);
    }
    @Test void validFrozenMapMayOmitUnboundNodeWithoutReadingLive(){
        TopicRouteSession s=new TopicRouteSession();s.setConfigSnapshotJson("{\"nodeJourney\":{}}");
        assertNull(ReflectionTestUtils.invokeMethod(runtime,"nodeBlockConfig",s,node(0)));
        s.setConfigSnapshotJson("{\"nodeJourney\":{\"1\":{\"frozen\":9}}}");
        JsonNode existing=ReflectionTestUtils.invokeMethod(runtime,"nodeBlockConfig",s,node(0));
        assertEquals(9,existing.get("frozen").intValue());verifyNoInteractions(reader,raw);
    }
    @Test void unboundNodeDoesNotReadBody(){assertNull(ReflectionTestUtils.invokeMethod(runtime,"nodeBlockConfig",node(0)));verifyNoInteractions(reader,raw);}
}
