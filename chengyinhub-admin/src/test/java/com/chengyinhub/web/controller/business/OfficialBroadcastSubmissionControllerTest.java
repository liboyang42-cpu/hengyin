package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.service.IOfficialBroadcastService;
import com.chengyinhub.business.service.IOfficialEventService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** Presentation boundary only; service state-machine and MySQL tests establish delivery facts separately. */
class OfficialBroadcastSubmissionControllerTest {
    private static class Fixture {
        final IOfficialBroadcastService broadcasts = mock(IOfficialBroadcastService.class);
        final IOfficialEventService events = mock(IOfficialEventService.class);
        final IOfficialEventV2Service v2 = mock(IOfficialEventV2Service.class);
        final BusinessOfficialEventController controller = new BusinessOfficialEventController() {
            @Override public Long getUserId() { return 42L; }
        };

        Fixture() {
            ReflectionTestUtils.setField(controller, "officialBroadcastService", broadcasts);
            ReflectionTestUtils.setField(controller, "officialEventService", events);
            ReflectionTestUtils.setField(controller, "officialEventV2Service", v2);
        }

        OfficialBroadcast saved(Integer reached, int status) {
            OfficialBroadcast result = new OfficialBroadcast();
            result.setId(71L);
            result.setReachedCount(reached);
            result.setStatus(status);
            return result;
        }

        void v2Event() {
            OfficialEvent event = new OfficialEvent();
            event.setId(7L);
            event.setContractVersion(2);
            when(events.adminGet(7L)).thenReturn(event);
        }

        void assertSubmitted(AjaxResult response) {
            assertEquals(200, response.get("code"));
            assertEquals("通知已提交", response.get("msg"));
            assertEquals(71L, response.get("data"));
        }
    }

    @Test
    void legacyPublishReportsSubmissionForPendingPartialOrConfirmedDelivery() {
        Fixture f = new Fixture();
        OfficialBroadcast request = new OfficialBroadcast();
        for (Integer reached : new Integer[] { null, 0, 1 }) {
            for (int state : new int[] { OfficialBroadcast.ST_PENDING, OfficialBroadcast.ST_SENT }) {
                when(f.broadcasts.publish(request, null)).thenReturn(f.saved(reached, state));
                f.assertSubmitted(f.controller.broadcast(request));
            }
        }
        verifyNoInteractions(f.v2);
    }

    @Test
    void v2PendingUnknownOrPartialDeliveryCannotBecomeAllDeliveredMessage() {
        Fixture f = new Fixture();
        f.v2Event();
        when(f.v2.resolveAuthorizedOfficialMember(42L)).thenReturn(101L);
        OfficialBroadcast request = new OfficialBroadcast();
        request.setEventId(7L);
        for (int reached : new int[] { 0, 1 }) {
            when(f.v2.publishBroadcastForOperator(request, 101L))
                    .thenReturn(f.saved(reached, OfficialBroadcast.ST_PENDING));
            f.assertSubmitted(f.controller.broadcast(request));
        }
        verify(f.v2, times(2)).publishBroadcastForOperator(request, 101L);
        verifyNoInteractions(f.broadcasts);
    }

    @Test
    void missingOperatorMappingStillRejectsWithoutPublishing() {
        Fixture f = new Fixture();
        f.v2Event();
        // The real resolver returns null for missing/invalid authorization; Mockito's boxed Long default is 0L.
        when(f.v2.resolveAuthorizedOfficialMember(42L)).thenReturn(null);
        OfficialBroadcast request = new OfficialBroadcast();
        request.setEventId(7L);
        AjaxResult rejected = f.controller.broadcast(request);
        assertNotEquals(200, rejected.get("code"));
        assertTrue(rejected.get("msg").toString().contains("未绑定有效官方会员授权"));
        verify(f.events).adminGet(7L);
        verify(f.v2).resolveAuthorizedOfficialMember(42L);
        verify(f.v2, never()).publishBroadcastForOperator(any(), any());
        verifyNoMoreInteractions(f.v2);
        verifyNoInteractions(f.broadcasts);
    }

    @Test
    void serviceRejectionCannotBecomeSuccessfulSubmissionReceipt() {
        Fixture f = new Fixture();
        when(f.broadcasts.publish(any(), isNull())).thenThrow(new ServiceException("广播拒绝"));
        assertThrows(ServiceException.class, () -> f.controller.broadcast(new OfficialBroadcast()));
    }
}
