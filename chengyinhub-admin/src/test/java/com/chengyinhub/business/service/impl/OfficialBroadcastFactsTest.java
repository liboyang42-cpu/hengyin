package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.mapper.OfficialAudienceMapper;
import com.chengyinhub.business.mapper.OfficialBroadcastLogMapper;
import com.chengyinhub.business.mapper.OfficialBroadcastMapper;
import com.chengyinhub.business.service.INotifyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OfficialBroadcastFactsTest {
    @Test
    void falseNotificationResultNeverMarksSubscribeSuccessful() {
        for (boolean accepted : new boolean[] { false, true }) {
            OfficialBroadcastServiceImpl service = new OfficialBroadcastServiceImpl();
            OfficialBroadcastLogMapper logs = mock(OfficialBroadcastLogMapper.class);
            OfficialAudienceMapper audience = mock(OfficialAudienceMapper.class);
            INotifyService notify = mock(INotifyService.class);
            ReflectionTestUtils.setField(service, "logMapper", logs);
            ReflectionTestUtils.setField(service, "audienceMapper", audience);
            ReflectionTestUtils.setField(service, "notifyService", notify);
            ReflectionTestUtils.setField(service, "broadcastMapper", mock(OfficialBroadcastMapper.class));
            when(audience.selectPlayerIds(null)).thenReturn(Collections.singletonList(7L));
            when(logs.insertIgnore(any())).thenReturn(1);
            when(notify.notifyOfficialBroadcast(eq(7L), anyString(), anyString())).thenReturn(accepted);
            OfficialBroadcast bc = new OfficialBroadcast();
            bc.setId(11L); bc.setTitle("notice"); bc.setAudience("player"); bc.setChannels("subscribe");
            service.dispatch(bc);
            verify(logs, times(accepted ? 1 : 0)).markReached(11L, 7L, "subscribe");
            assertEquals(Integer.valueOf(0), bc.getReachedCount());
        }
    }

    @Test
    void statsRetainExistingReadScopeAndReturnRecordedFactsOnly() {
        OfficialBroadcastServiceImpl service = new OfficialBroadcastServiceImpl();
        OfficialBroadcastMapper broadcasts = mock(OfficialBroadcastMapper.class);
        OfficialBroadcastLogMapper logs = mock(OfficialBroadcastLogMapper.class);
        ReflectionTestUtils.setField(service, "broadcastMapper", broadcasts);
        ReflectionTestUtils.setField(service, "logMapper", logs);
        OfficialBroadcast bc = new OfficialBroadcast(); bc.setId(11L); bc.setStatus(1);
        when(broadcasts.selectOfficialBroadcastById(11L)).thenReturn(bc);
        when(logs.countReached(11L)).thenReturn(0);
        when(logs.selectChannelStats(11L)).thenReturn(Collections.singletonList(Collections.<String, Object>singletonMap("unconfirmed", 1)));
        java.util.Map<String, Object> stats = service.reachStats(11L, null);
        assertEquals(0, stats.get("reached"));
        assertTrue(stats.containsKey("channelStats"));
        assertFalse(stats.containsKey("wechatAccepted"));
        assertFalse(stats.containsKey("delivered"));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> service.reachStats(99L, null));
        verify(logs, never()).countReached(99L);
    }
    @Test
    void sameRequestAndConcurrentDuplicateNeverDispatchAgainButChangedPayloadIsRejected() {
        OfficialBroadcastServiceImpl service = new OfficialBroadcastServiceImpl();
        OfficialBroadcastMapper mapper = mock(OfficialBroadcastMapper.class);
        OfficialAudienceMapper audience = mock(OfficialAudienceMapper.class);
        ReflectionTestUtils.setField(service, "broadcastMapper", mapper);
        ReflectionTestUtils.setField(service, "audienceMapper", audience);
        OfficialBroadcast existing = request(); existing.setId(11L);
        when(mapper.selectByRequest("ADMIN:1", "original")).thenReturn(existing);
        assertSame(existing, service.publish(request(), null));
        verify(mapper, never()).insertOfficialBroadcast(any()); verifyNoInteractions(audience);
        OfficialBroadcast changed = request(); changed.setTitle("different");
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> service.publish(changed, null));
        when(mapper.selectByRequest("ADMIN:1", "original")).thenReturn(null, existing);
        when(mapper.insertOfficialBroadcast(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));
        assertSame(existing, service.publish(request(), null)); verifyNoInteractions(audience);
    }

    private OfficialBroadcast request() {
        OfficialBroadcast request = new OfficialBroadcast(); request.setRequestScope("ADMIN:1"); request.setRequestId("original");
        request.setTitle("notice"); request.setAudience("player"); request.setChannels("inapp"); request.setCopyMode(1); return request;
    }

    @Test
    void clientCannotSupplyOrReadRequestOwnerScope() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        OfficialBroadcast request = json.readValue("{\"requestId\":\"original\",\"requestScope\":\"ADMIN:999\"}", OfficialBroadcast.class);
        assertNull(request.getRequestScope());
        request.setRequestScope("ADMIN:1");
        assertFalse(json.writeValueAsString(request).contains("requestScope"));
    }

}
