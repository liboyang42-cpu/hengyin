package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.impl.GameSessionRuntimeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Date;

/** NOT_RUN. Model an old ordinary snapshot and a committed unique-key winner independently. */
class OWN_C06_09Test {
    @Test void duplicatePrepareUsesCommittedWinnerWithoutRecreatingStations() {
        GameSessionRuntimeServiceImpl service = new GameSessionRuntimeServiceImpl();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        GameSessionRuntimeMapper runtime = mock(GameSessionRuntimeMapper.class);
        ReflectionTestUtils.setField(service, "topicMapper", topics);
        ReflectionTestUtils.setField(service, "runtimeMapper", runtime);
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock) () -> new Date());
        CmsTopic topic = new CmsTopic(); topic.setProductType(com.chengyinhub.common.constant.ProductType.ORIENTEERING);
        when(topics.selectCmsTopicByIdForUpdate(20L)).thenReturn(topic);
        CmsActivity activity = new CmsActivity(); activity.setId(10L); activity.setTopicId(20L);
        GameSession winner = new GameSession(); winner.setId(30L); winner.setActivityId(10L); winner.setRevision(2);
        when(runtime.insertSession(any())).thenReturn(0);
        when(runtime.selectSessionByActivity(10L)).thenReturn(null);
        when(runtime.selectSessionByActivityForUpdate(10L)).thenReturn(winner);
        Object result = ReflectionTestUtils.invokeMethod(service, "prepare", GameActorContext.club(7L), activity);
        assertSame(winner, ReflectionTestUtils.getField(result, "session"));
        verify(runtime).selectSessionByActivityForUpdate(10L);
        verify(runtime, never()).selectSessionByActivity(10L);
        verify(runtime, never()).insertStationsFromTopic(anyLong(), anyLong(), anyLong(), any());
    }
    @Test void duplicatePrepareWithoutAnyCommittedWinnerStillFailsClosed() {
        GameSessionRuntimeServiceImpl service = new GameSessionRuntimeServiceImpl();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        GameSessionRuntimeMapper runtime = mock(GameSessionRuntimeMapper.class);
        ReflectionTestUtils.setField(service, "topicMapper", topics);
        ReflectionTestUtils.setField(service, "runtimeMapper", runtime);
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock) () -> new Date());
        CmsTopic topic = new CmsTopic(); topic.setProductType(com.chengyinhub.common.constant.ProductType.ORIENTEERING);
        when(topics.selectCmsTopicByIdForUpdate(20L)).thenReturn(topic);
        CmsActivity activity = new CmsActivity(); activity.setId(10L); activity.setTopicId(20L);
        GameSessionException error = assertThrows(GameSessionException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "prepare", GameActorContext.club(7L), activity));
        assertEquals("GAME_STATE_CONFLICT", error.getErrorCode());
        verify(runtime, never()).insertStationsFromTopic(anyLong(), anyLong(), anyLong(), any());
    }
}
