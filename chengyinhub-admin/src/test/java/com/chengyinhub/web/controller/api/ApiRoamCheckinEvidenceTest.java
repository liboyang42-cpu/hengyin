package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.chengyinhub.business.locationtrust.LocationTrustConfig;
import com.chengyinhub.business.locationtrust.LocationTrustService;

/** GAME-4: ApiRoamCheckinXpCap + ApiRoamCheckinRequiresFix. */
class ApiRoamCheckinEvidenceTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void firstVisitXpIsCappedAtTenNewPlacesADay() {
        assertEquals(20, ApiRoamController.visitXpWithDailyCap(0, 20, ApiRoamController.ROAM_VISIT_XP_DAILY_LIMIT));
        assertEquals(20, ApiRoamController.visitXpWithDailyCap(180, 20, 200));
        assertEquals(10, ApiRoamController.visitXpWithDailyCap(190, 20, 200));
        assertEquals(0, ApiRoamController.visitXpWithDailyCap(200, 20, 200));
        assertEquals(0, ApiRoamController.visitXpWithDailyCap(500, 20, 200));
    }

    private ApiRoamController controller(boolean requiresFix) {
        LocationTrustService trust = mock(LocationTrustService.class);
        when(trust.config()).thenReturn(LocationTrustConfig.read(
                k -> "location.trust.checkinRequiresFix".equals(k) ? String.valueOf(requiresFix) : null));
        ApiRoamController controller = new ApiRoamController();
        ReflectionTestUtils.setField(controller, "locationTrust", trust);
        return controller;
    }

    private static void request(String accuracy, String timestamp) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (accuracy != null) req.setParameter("locAccuracy", accuracy);
        if (timestamp != null) req.setParameter("locTimestamp", timestamp);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void switchOffKeepsOldClientsWorking() {
        request(null, null);
        assertFalse(controller(false).checkinFixMissing(31.23, 121.47));
    }

    @Test
    void switchOnNeedsTheDeviceFixSignals() {
        request(null, null);
        assertTrue(controller(true).checkinFixMissing(31.23, 121.47));
        request("12", null);
        assertTrue(controller(true).checkinFixMissing(31.23, 121.47));
        request("12", String.valueOf(System.currentTimeMillis()));
        assertFalse(controller(true).checkinFixMissing(31.23, 121.47));
    }

    @Test
    void switchDefaultsOff() {
        assertFalse(LocationTrustConfig.defaults().checkinRequiresFix());
        assertEquals(LocationTrustConfig.JitterMode.ACCURACY, LocationTrustConfig.defaults().jitterMode());
        assertEquals(10, LocationTrustConfig.defaults().windowMinutes());
    }
}
