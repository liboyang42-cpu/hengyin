package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Only present-null frozen rules are isolated from live topic fallback. */
class OWN_C06_29Test {
    private TopicRouteRuntimeServiceImpl service;
    private CmsTopicMapper topics;
    private TopicRouteSession session;
    private CmsTopic live;
    @BeforeEach void setup() {
        topics=mock(CmsTopicMapper.class);
        service=new TopicRouteRuntimeServiceImpl(
                mock(com.chengyinhub.business.mapper.TopicRouteSessionMapper.class),
                topics,
                mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class),
                mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class),
                mock(com.chengyinhub.business.mapper.PlayerNodeProgressMapper.class),
                mock(com.chengyinhub.business.mapper.CmsActivityMapper.class),
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class),
                mock(com.chengyinhub.business.mapper.PlayerActivityRoleMapper.class),
                mock(com.chengyinhub.business.service.IShopDayProofService.class),
                () -> 12345L,
                () -> 10,
                java.time.Clock.systemUTC(),
                com.chengyinhub.business.service.support.RouteReachabilityProvider.UNKNOWN);
        session=new TopicRouteSession();session.setTopicId(20L);
        live=new CmsTopic();live.setId(20L);live.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true}");
        when(topics.selectCmsTopicById(20L)).thenReturn(live);
    }
    private JourneyRules read(String entry) {
        return "sessionRules".equals(entry) ? ReflectionTestUtils.invokeMethod(service,entry,session)
                : ReflectionTestUtils.invokeMethod(service,entry,session,live);
    }
    @Test void missingKeyPreservesBothLiveFallbacks() {
        session.setConfigSnapshotJson("{}");
        assertTrue(read("sessionRules").isCheckEnabled());
        assertTrue(read("journeyRules").isCheckEnabled());
        verify(topics).selectCmsTopicById(20L);
    }
    @Test void presentNullRejectsBothEntrancesWithoutLoadingLiveTopic() {
        session.setConfigSnapshotJson("{\"journeyRules\":null}");
        for(String entry:new String[]{"sessionRules","journeyRules"}) {
            IllegalStateException error=assertThrows(IllegalStateException.class,()->read(entry));
            assertEquals("旅程数值规则不可用",error.getMessage());
        }
        verifyNoInteractions(topics);
    }
    @Test void versionedFrozenObjectPreservesDefaultRulesInsteadOfLiveCheckEnabled() {
        session.setConfigSnapshotJson("{\"journeyRules\":{\"schemaVersion\":1}}");
        assertFalse(read("sessionRules").isCheckEnabled());assertFalse(read("journeyRules").isCheckEnabled());
        verifyNoInteractions(topics);
    }
    @Test void wrongNonNullTypeContinuesThroughOriginalValidator() {
        session.setConfigSnapshotJson("{\"journeyRules\":[]}");
        assertThrows(IllegalArgumentException.class,()->read("sessionRules"));
        assertThrows(IllegalArgumentException.class,()->read("journeyRules"));
        verifyNoInteractions(topics);
    }
}
