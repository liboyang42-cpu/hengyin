package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.impl.GameSessionRuntimeServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Source regression NOT_RUN; actual transaction/locking still needs dedicated MySQL. */
class OWN_C06_05Test {
    @Test void prepareLocksCurrentTopicBeforeCapturingSessionAndNodes() {
        GameSessionRuntimeServiceImpl service = new GameSessionRuntimeServiceImpl();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        GameSessionRuntimeMapper runtime = mock(GameSessionRuntimeMapper.class);
        ReflectionTestUtils.setField(service, "topicMapper", topics);
        ReflectionTestUtils.setField(service, "runtimeMapper", runtime);
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock) () -> new Date(1000));
        CmsActivity activity = new CmsActivity(); activity.setId(10L); activity.setTopicId(20L);
        activity.setStartDate(new Date(1000)); activity.setEndDate(new Date(2000));
        CmsTopic topic = new CmsTopic(); topic.setId(20L);
        topic.setProductType(com.chengyinhub.common.constant.ProductType.ORIENTEERING);
        when(topics.selectCmsTopicByIdForUpdate(20L)).thenReturn(topic);
        when(runtime.insertSession(any())).thenAnswer(call -> {
            ((GameSession) call.getArgument(0)).setId(30L); return 1;
        });
        GameStationRun station = new GameStationRun(); station.setId(40L); station.setNodeId(50L);
        when(runtime.selectStations(30L)).thenReturn(Collections.singletonList(station));
        assertNotNull(ReflectionTestUtils.invokeMethod(service, "prepare", GameActorContext.club(7L), activity));
        InOrder order = inOrder(topics, runtime);
        order.verify(topics).selectCmsTopicByIdForUpdate(20L);
        order.verify(runtime).insertSession(any());
        order.verify(runtime).insertStationsFromTopic(eq(30L), eq(10L), eq(20L), any());
        verify(topics, never()).selectCmsTopicById(anyLong());
    }
    @Test void missingLockedTopicDoesNotFallBackToAnOldOrdinarySnapshot() {
        GameSessionRuntimeServiceImpl service = new GameSessionRuntimeServiceImpl();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        GameSessionRuntimeMapper runtime = mock(GameSessionRuntimeMapper.class);
        ReflectionTestUtils.setField(service, "topicMapper", topics);
        ReflectionTestUtils.setField(service, "runtimeMapper", runtime);
        CmsActivity activity = new CmsActivity(); activity.setId(10L); activity.setTopicId(20L);
        assertThrows(GameSessionException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "prepare", GameActorContext.club(7L), activity));
        verify(runtime, never()).insertSession(any());
        verify(topics, never()).selectCmsTopicById(anyLong());
    }
}
