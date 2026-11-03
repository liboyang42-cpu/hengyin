package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.service.IOrientSessionService;
import com.chengyinhub.business.service.orienteering.EventScoreVersioning;
import com.chengyinhub.common.core.domain.AjaxResult;

class ApiOrientSessionControllerTest {

    private ApiOrientSessionController controller;
    private IOrientSessionService sessions;

    @BeforeEach
    void setUp() {
        sessions = mock(IOrientSessionService.class);
        controller = spy(new ApiOrientSessionController());
        ReflectionTestUtils.setField(controller, "orientSessionService", sessions);
        doReturn(7L).when(controller).getAppUserId();
    }

    @Test
    void receiptKeepsSameSessionAndRejectsAnotherSession() {
        Map<String, Object> same = new LinkedHashMap<String, Object>();
        same.put("replayed", Boolean.TRUE);
        same.put("sessionId", Long.valueOf(30L));
        when(sessions.receipt(7L, 30L, "orient-ses-01")).thenReturn(same);
        assertSame(same, controller.receipt(30L, "orient-ses-01").get(AjaxResult.DATA_TAG));

        Map<String, Object> bare = new LinkedHashMap<String, Object>();
        bare.put("replayed", Boolean.TRUE);
        when(sessions.receipt(7L, 30L, "orient-ses-bare")).thenReturn(bare);
        assertSame(bare, controller.receipt(30L, "orient-ses-bare").get(AjaxResult.DATA_TAG));

        Map<String, Object> other = new LinkedHashMap<String, Object>();
        other.put("replayed", Boolean.TRUE);
        other.put("sessionId", Long.valueOf(99L));
        when(sessions.receipt(7L, 30L, "orient-ses-other")).thenReturn(other);
        AjaxResult rejected = controller.receipt(30L, "orient-ses-other");
        assertEquals(404, rejected.get(AjaxResult.CODE_TAG));
        assertEquals(EventScoreVersioning.SESSION_RECEIPT_NOT_FOUND, rejected.get("err"));
    }
}
