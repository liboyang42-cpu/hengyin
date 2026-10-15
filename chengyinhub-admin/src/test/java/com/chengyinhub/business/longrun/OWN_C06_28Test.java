package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Corrupt stored blocks must not silently load the author's current template. */
class OWN_C06_28Test {
    private TopicRouteRuntimeServiceImpl service;
    private CmsMemberTemplateMapper templates;
    private TopicRouteSession session;
    private CmsTopicNode node;
    @BeforeEach void setup() {
        service=new TopicRouteRuntimeServiceImpl();templates=mock(CmsMemberTemplateMapper.class);
        ReflectionTestUtils.setField(service,"templateMapper",templates);
        session=new TopicRouteSession();node=new CmsTopicNode();node.setId(40L);node.setTemplateId(50L);
        CmsMemberTemplate live=new CmsMemberTemplate();live.setAdvancedConfigJson("{\"marker\":\"author-new\"}");
        when(templates.selectCmsMemberTemplateById(50L)).thenReturn(live);
    }
    private JsonNode block(String snapshot) {
        session.setConfigSnapshotJson(snapshot);return ReflectionTestUtils.invokeMethod(service,"nodeBlockConfig",session,node);
    }
    @Test void missingKeyAndMissingNodeKeepLegacyTemplateFallback() {
        assertEquals("author-new",block("{}").path("marker").asText());
        assertEquals("author-new",block("{\"nodeJourney\":{}}").path("marker").asText());
        verify(templates,times(2)).selectCmsMemberTemplateById(50L);
    }
    @Test void presentBadRootRejectsInsteadOfReadingLive() {
        for(String value:new String[]{"null","[]","17","\"bad\""}) {
            IllegalStateException error=assertThrows(IllegalStateException.class,()->block("{\"nodeJourney\":"+value+"}"));
            assertEquals("节点玩法配置不可用",error.getMessage());
        }
        verifyNoInteractions(templates);
    }
    @Test void presentBadNodeRejectsInsteadOfReadingLive() {
        for(String value:new String[]{"null","[]","17","\"bad\""}) {
            IllegalStateException error=assertThrows(IllegalStateException.class,()->block("{\"nodeJourney\":{\"40\":"+value+"}}"));
            assertEquals("节点玩法配置不可用",error.getMessage());
        }
        verifyNoInteractions(templates);
    }
    @Test void emptyFrozenObjectAndValidFrozenRulesDoNotReadLive() {
        assertTrue(block("{\"nodeJourney\":{\"40\":{}}}").isObject());
        assertEquals("frozen",block("{\"nodeJourney\":{\"40\":{\"marker\":\"frozen\"}}}").path("marker").asText());
        verifyNoInteractions(templates);
    }
}
