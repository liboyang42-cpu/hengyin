package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real route service, mocked storage: a reminder read never initializes a journey. */
class NativeReminderOpeningReadOnlyTest {
    private TopicRouteSessionMapper sessions;
    private CmsTopicMapper topics;
    private TopicRouteRuntimeServiceImpl service;
    @BeforeEach void setup() {
        sessions=mock(TopicRouteSessionMapper.class);topics=mock(CmsTopicMapper.class);
        service=com.chengyinhub.business.service.support.SyntheticLegacyRuntimeReader.attach(new TopicRouteRuntimeServiceImpl(sessions,topics,mock(CmsTopicNodeMapper.class),
                mock(PlayerNodeProgressMapper.class),mock(CmsActivityMapper.class),mock(CmsRegistrationMapper.class)), null);
    }
    @Test void missingSessionWithOpeningFailsClosedWithoutCreation() {
        CmsTopic topic=new CmsTopic();topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":90}");
        when(topics.selectCmsTopicById(19L)).thenReturn(topic);
        ServiceException e=assertThrows(ServiceException.class,()->service.requireOpeningAccessReadOnly(7L,19L,0L,29L));
        assertEquals("TIME_WINDOW_OPENING_REQUIRED",((Map<?,?>)e.getData()).get("reasonCode"));
        verify(sessions).selectCurrentByOwner(0L,19L,7L);
        verify(sessions).selectByOwner(0L,19L,7L,"default");verifyNoMoreInteractions(sessions);
        // 当前局政策与开场规则各做一次只读查询。
        verify(topics,times(2)).selectCmsTopicById(19L);verifyNoMoreInteractions(topics);
    }
    @Test void ordinaryNoOpeningTopicNeedsNoSessionCreation() {
        CmsTopic topic=new CmsTopic();topic.setJourneyStoryJson("{}");when(topics.selectCmsTopicById(19L)).thenReturn(topic);
        assertDoesNotThrow(()->service.requireOpeningAccessReadOnly(7L,19L,0L,29L));
        verify(sessions).selectCurrentByOwner(0L,19L,7L);
        verify(sessions).selectByOwner(0L,19L,7L,"default");verifyNoMoreInteractions(sessions);
    }
    @Test void existingOpeningPolicyIsReusedWithoutWrites() {
        TopicRouteSession session=new TopicRouteSession();
        session.setMemberId(7L);session.setTopicId(19L);session.setActivityId(0L);session.setPlayInstanceId("default");
        session.setConfigSnapshotJson("{\"openingChapter\":{\"chapterId\":90,\"blocks\":[]}}");
        session.setStateJson("{\"tags\":[]}");when(sessions.selectByOwner(0L,19L,7L,"default")).thenReturn(session);
        assertThrows(ServiceException.class,()->service.requireOpeningAccessReadOnly(7L,19L,0L,29L));
        session.setStateJson("{\"tags\":[\"sys.opening.done\"]}");
        assertDoesNotThrow(()->service.requireOpeningAccessReadOnly(7L,19L,0L,29L));
        verify(sessions,times(2)).selectCurrentByOwner(0L,19L,7L);
        verify(sessions,times(2)).selectByOwner(0L,19L,7L,"default");verifyNoMoreInteractions(sessions);verifyNoInteractions(topics);
    }
    @Test void foreignCurrentCandidateCannotFallBackToLegacyOpening() {
        for (int mismatch = 0; mismatch < 3; mismatch++) {
            reset(sessions);
            TopicRouteSession foreign = new TopicRouteSession();
            foreign.setMemberId(mismatch == 0 ? 8L : 7L);
            foreign.setTopicId(mismatch == 1 ? 20L : 19L);
            foreign.setActivityId(mismatch == 2 ? 1L : 0L);
            when(sessions.selectCurrentByOwner(0L,19L,7L)).thenReturn(foreign);
            ServiceException error = assertThrows(ServiceException.class,
                    () -> service.requireOpeningAccessReadOnly(7L,19L,0L,29L));
            assertEquals("无权访问这一局", error.getMessage());
            verify(sessions).selectCurrentByOwner(0L,19L,7L);
            verifyNoMoreInteractions(sessions);
            verifyNoInteractions(topics);
        }
    }

}
