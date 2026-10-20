package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayEncounterServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Real public Encounter consumer; mocks only lookup/proof boundaries. */
class E05_17Test {
    private PlayEncounterServiceImpl service;
    private TopicRouteSession run;
    private TopicRouteSessionMapper sessions;
    private static final String LIVE = "{\"qa\":{\"enabled\":true},\"branch\":{\"enabled\":true,"
            + "\"steps\":[{\"terminal\":true,\"outcomeCode\":\"LIVE_LATE\",\"outcomeLabel\":\"late edit\"}]}}";

    @BeforeEach
    void setup() {
        service = new PlayEncounterServiceImpl();
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
        sessions = mock(TopicRouteSessionMapper.class);
        IShopDayProofService proof = mock(IShopDayProofService.class);
        ReflectionTestUtils.setField(service, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateService", templates);
        ReflectionTestUtils.setField(service, "topicRouteSessionMapper", sessions);
        ReflectionTestUtils.setField(service, "shopDayProofService", proof);
        ReflectionTestUtils.setField(service, "npcProfileService", mock(NpcProfileService.class));
        ReflectionTestUtils.setField(service, "playerNodeProgressService", mock(IPlayerNodeProgressService.class));
        ReflectionTestUtils.setField(service, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        CmsTopicNode node = new CmsTopicNode(); node.setId(101L); node.setTopicId(73L); node.setTemplateId(501L);
        when(nodes.selectCmsTopicNodeById(101L)).thenReturn(node);
        CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(501L); template.setAdvancedConfigJson(LIVE);
        when(templates.selectCmsMemberTemplateById(501L)).thenReturn(template);
        run = new TopicRouteSession(); run.setId(9001L); run.setMemberId(71L); run.setTopicId(73L);
        run.setActivityId(0L); run.setVersion(3);
        run.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[],\"completedNodeIds\":[]}");
        run.setStatus(TopicRouteSession.STATUS_COMPLETED);
        run.setConfigSnapshotJson(snapshot(false));
        when(sessions.selectByOwner(0L, 73L, 71L, "default")).thenReturn(run);
        when(sessions.selectMemberTopicSessions(71L, 73L)).thenReturn(Collections.singletonList(run));
        when(proof.hasArrived(71L, 0L, 101L)).thenReturn(true);
    }

    @Test
    void encounterUsesNewRunningFreezeInsteadOfCompletedDefaultProjection() {
        TopicRouteSession current = current(9002L, TopicRouteSession.STATUS_RUNNING, true);
        when(sessions.selectCurrentByOwner(0L, 73L, 71L)).thenReturn(current);
        Map<String, Object> result = service.resolve(71L, 73L, 0L, 101L);
        assertEquals(9002L, result.get("runId"));
        assertEquals(12, result.get("stateVersion"));
        assertTrue(((List<?>) result.get("allowedActions")).contains("qa"));
        verify(sessions, never()).selectByOwner(0L, 73L, 71L, "default");
    }

    @Test
    void latestEndedEncounterDoesNotResurrectOlderRunningFreeze() {
        run.setStatus(TopicRouteSession.STATUS_RUNNING);
        run.setConfigSnapshotJson(snapshot(true));
        when(sessions.selectCurrentByOwner(0L, 73L, 71L))
                .thenReturn(current(9003L, TopicRouteSession.STATUS_ENDED, false));
        Map<String, Object> result = service.resolve(71L, 73L, 0L, 101L);
        assertEquals(9003L, result.get("runId"));
        assertTrue(((List<?>) result.get("allowedActions")).isEmpty());
        verify(sessions, never()).selectByOwner(0L, 73L, 71L, "default");
    }

    @Test
    void encounterRejectsForeignMemberTopicOrActivityFromCurrentLookup() {
        for (String foreign : new String[]{"member", "topic", "activity"}) {
            TopicRouteSession current = current(9002L, TopicRouteSession.STATUS_RUNNING, true);
            if ("member".equals(foreign)) current.setMemberId(72L);
            if ("topic".equals(foreign)) current.setTopicId(74L);
            if ("activity".equals(foreign)) current.setActivityId(99L);
            when(sessions.selectCurrentByOwner(0L, 73L, 71L)).thenReturn(current);
            ServiceException error = assertThrows(ServiceException.class,
                    () -> service.resolve(71L, 73L, 0L, 101L), foreign);
            assertEquals("无权访问这一局", error.getMessage());
        }
    }

    @Test
    void omittedActivityStillRejectsAmbiguousDefaultScopesBeforeCurrentProjection() {
        TopicRouteSession anotherActivity = current(9004L, TopicRouteSession.STATUS_RUNNING, true);
        anotherActivity.setActivityId(99L);
        when(sessions.selectMemberTopicSessions(71L, 73L)).thenReturn(java.util.Arrays.asList(run, anotherActivity));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.resolve(71L, 73L, null, 101L));
        assertEquals("存在多个场次，请指定 activityId", error.getMessage());
        verify(sessions, never()).selectCurrentByOwner(anyLong(), anyLong(), anyLong());
    }

    @Test
    void omittedUniqueActivityProjectsCurrentAndMissingCurrentKeepsDefaultCompatibility() {
        when(sessions.selectCurrentByOwner(0L, 73L, 71L))
                .thenReturn(current(9002L, TopicRouteSession.STATUS_RUNNING, true));
        assertEquals(9002L, service.resolve(71L, 73L, null, 101L).get("runId"));
        when(sessions.selectCurrentByOwner(0L, 73L, 71L)).thenReturn(null);
        Map<String, Object> legacy = service.resolve(71L, 73L, null, 101L);
        assertEquals(9001L, legacy.get("runId"));
        assertTrue(((List<?>) legacy.get("allowedActions")).isEmpty());
    }

    private TopicRouteSession current(long id, int status, boolean qa) {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(id); session.setMemberId(71L); session.setTopicId(73L); session.setActivityId(0L);
        session.setStatus(status); session.setVersion(12); session.setPlayInstanceId("replay-current");
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[],\"completedNodeIds\":[]}");
        session.setConfigSnapshotJson(snapshot(qa));
        return session;
    }
    private static String snapshot(boolean qa) {
        return "{\"nodeJourney\":{\"101\":{\"playables\":" + (qa ? "{\"qa\":true}" : "{}")
                + ",\"branchOutcomes\":[]}},\"edges\":[]}";
    }
}
