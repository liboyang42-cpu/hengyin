package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.service.IOfficialBroadcastService;
import com.chengyinhub.business.service.IOfficialEventService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiOfficialMyPublishedTest {
    private final IOfficialEventService events = mock(IOfficialEventService.class);
    private final IOfficialBroadcastService broadcasts = mock(IOfficialBroadcastService.class);
    private final ISysConfigService config = mock(ISysConfigService.class);

    private ApiOfficialEventController controller(Long memberId) {
        ApiOfficialEventController controller = spy(new ApiOfficialEventController());
        doReturn(memberId).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "officialEventService", events);
        ReflectionTestUtils.setField(controller, "officialBroadcastService", broadcasts);
        ReflectionTestUtils.setField(controller, "configService", config);
        return controller;
    }

    @Test
    void missingOrNonMatchingWhitelistDeniesWithoutReadingProtectedData() {
        ApiOfficialEventController controller = controller(101L);
        for (String whitelist : new String[] {null, "", " ", "1101,1010", "7, 8"}) {
            when(config.selectConfigByKey("official_publish_whitelist")).thenReturn(whitelist);

            AjaxResult result = controller.myPublished();

            assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
            assertEquals("无官方发布权限", result.get(AjaxResult.MSG_TAG));
            assertFalse(result.containsKey(AjaxResult.DATA_TAG));
        }
        verifyNoInteractions(events, broadcasts);
    }

    @Test
    void missingInvalidOrFailedTokenIdentityNeverReadsWhitelistOrProtectedData() {
        for (Long memberId : new Long[] {null, 0L, -1L}) {
            AjaxResult result = controller(memberId).myPublished();
            assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
            assertEquals("无官方发布权限", result.get(AjaxResult.MSG_TAG));
        }
        ApiOfficialEventController controller = controller(101L);
        doThrow(new IllegalStateException("token unavailable")).when(controller).getAppUserId();
        AjaxResult result = controller.myPublished();
        assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
        assertEquals("无官方发布权限", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(config, events, broadcasts);
    }

    @Test
    void exactTrimmedWhitelistMemberKeepsBothReadsScopedToCurrentMember() {
        ApiOfficialEventController controller = controller(101L);
        when(config.selectConfigByKey("official_publish_whitelist")).thenReturn("7, 101 ,8");
        List<OfficialEvent> ownEvents = List.of(new OfficialEvent());
        List<OfficialBroadcast> ownBroadcasts = List.of(new OfficialBroadcast());
        when(events.listBySender(101L)).thenReturn(ownEvents);
        when(broadcasts.listBySender(101L, 101L)).thenReturn(ownBroadcasts);

        AjaxResult result = controller.myPublished();

        assertTrue(result.isSuccess());
        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        assertEquals("操作成功", result.get(AjaxResult.MSG_TAG));
        assertEquals(Map.of("events", ownEvents, "broadcasts", ownBroadcasts), result.get(AjaxResult.DATA_TAG));
        verify(events).listBySender(101L);
        verify(broadcasts).listBySender(101L, 101L);
        verifyNoMoreInteractions(events, broadcasts);
    }

    @Test
    void configurationFailureIsNotDisguisedAsPermissionDenial() {
        ApiOfficialEventController controller = controller(101L);
        IllegalStateException failure = new IllegalStateException("config unavailable");
        when(config.selectConfigByKey("official_publish_whitelist")).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, controller::myPublished));
        verifyNoInteractions(events, broadcasts);
    }
}
