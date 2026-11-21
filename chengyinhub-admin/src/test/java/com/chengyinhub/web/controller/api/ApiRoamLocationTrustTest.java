package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.locationtrust.LocationTrustService;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.LocationTrustMapper;
import com.chengyinhub.business.mapper.MemberVisitedPoiMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.common.core.domain.AjaxResult;

/** P8: the roam endpoints with the trust service in place (SEC-06 check-in, SEC-08 shop visit). */
class ApiRoamLocationTrustTest {
    private static final long PLAYER = 9L;
    private static final long SID = 77L;
    private static final double LAT = 31.2304, LNG = 121.4737;

    private static class StubController extends ApiRoamController {
        @Override
        Long uid() { return PLAYER; }
    }

    private RoamMapper roamMapper;
    private LocationTrustMapper trustMapper;
    private MemberVisitedPoiMapper visited;
    private IGrowthService growth;
    private StubController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        roamMapper = mock(RoamMapper.class);
        trustMapper = mock(LocationTrustMapper.class);
        visited = mock(MemberVisitedPoiMapper.class);
        growth = mock(IGrowthService.class);
        controller = new StubController();
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "locationConsent",
                mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantMapper", mock(CmsRegistrationMerchantMapper.class));
        ReflectionTestUtils.setField(controller, "memberVisitedPoiMapper", visited);
        ReflectionTestUtils.setField(controller, "growthService", growth);
        ReflectionTestUtils.setField(controller, "submissionSafety",
                mock(com.chengyinhub.business.service.support.UgcSubmissionSafety.class));
        ReflectionTestUtils.setField(controller, "locationTrust", new LocationTrustService(null, trustMapper, null));
        when(visited.insertVisitedPoiIgnore(anyLong(), anyString(), anyString(), any(), any(), any())).thenReturn(1);

        RoamSession session = new RoamSession();
        session.setId(SID);
        session.setMemberId(PLAYER);
        session.setStatus(0);
        when(roamMapper.selectOwnedSessionForUpdate(SID, PLAYER)).thenReturn(session);
        RoamPoi poi = new RoamPoi();
        poi.setId(501L);
        poi.setName("测试据点");
        poi.setType(1);
        poi.setLat(BigDecimal.valueOf(LAT));
        poi.setLng(BigDecimal.valueOf(LNG));
        when(roamMapper.selectPlayerPoisByIds(any())).thenReturn(Collections.singletonList(poi));
        when(roamMapper.insertShopVisitIgnore(anyLong(), anyLong(), anyInt(), anyLong(), any(), any())).thenReturn(1);

        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void lastCleanFix(double lat, double lng, long agoMs) {
        Map<String, Object> row = new HashMap<>();
        row.put("lat", lat);
        row.put("lng", lng);
        row.put("receivedAt", new Date(System.currentTimeMillis() - agoMs));
        when(trustMapper.selectLastCleanFix(anyLong(), any())).thenReturn(row);
    }

    @Test
    void honestCheckin_withGoodSignals_stillGetsXp() {
        request.addParameter("locAccuracy", "8");
        request.addParameter("locTimestamp", String.valueOf(System.currentTimeMillis()));
        request.addParameter("locSimulated", "0");
        AjaxResult r = controller.checkin("咖啡馆", LAT, LNG, LAT, LNG);
        assertEquals(200, r.get("code"));
        verify(growth).addExp(PLAYER, 20, "点亮地点");
        verify(trustMapper).insertFix(any());
    }

    @Test
    void simulatedCheckin_isRecordedForReviewButEarnsNothing_andDoesNotBurnTheFirstVisit() {
        request.addParameter("locSimulated", "1");
        AjaxResult r = controller.checkin("咖啡馆", LAT, LNG, LAT, LNG);
        assertEquals(200, r.get("code"));
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals(Boolean.TRUE, data.get("uncredited"));
        assertEquals(0, data.get("xp"));
        assertEquals("SIMULATED", data.get("trustCode"));
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
        verify(visited, never()).insertVisitedPoiIgnore(anyLong(), anyString(), anyString(), any(), any(), any());
        verify(trustMapper).insertFix(any());
    }

    @Test
    void checkinWithAccuracyWorseThan200m_asksForARetry() {
        request.addParameter("locAccuracy", "450");
        AjaxResult r = controller.checkin("咖啡馆", LAT, LNG, LAT, LNG);
        assertNotEquals(200, r.get("code"));
        assertEquals("ACCURACY_POOR", r.get("trustCode"));
        assertEquals(Boolean.TRUE, r.get("retryable"));
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
    }

    @Test
    void sofaCheckin_teleportingFromAnotherDistrict_earnsNothing() {
        lastCleanFix(LAT - 0.05, LNG, 30_000L);       // ~5.5 km away 30 s ago
        AjaxResult r = controller.checkin("咖啡馆", LAT, LNG, LAT, LNG);
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals("SPEED_IMPOSSIBLE", data.get("trustCode"));
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
    }

    @Test
    void shopVisitAtImpossibleSpeed_isNotCredited() {
        lastCleanFix(LAT - 0.1, LNG, 10_000L);        // ~11 km away 10 s ago
        AjaxResult r = controller.shopVisit(String.valueOf(SID), 1, 501L, LAT, LNG);
        assertEquals(200, r.get("code"));
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals(Boolean.FALSE, data.get("recorded"));
        assertEquals(Boolean.TRUE, data.get("uncredited"));
        verify(roamMapper, never()).insertShopVisitIgnore(anyLong(), anyLong(), anyInt(), anyLong(), any(), any());
        verify(roamMapper, never()).recordAnonymousShopVisitor(anyLong(), anyInt(), anyLong());
    }

    @Test
    void shopVisitAtWalkingPace_isCredited() {
        lastCleanFix(LAT - 0.01, LNG, 900_000L);      // ~1.1 km 15 min ago
        AjaxResult r = controller.shopVisit(String.valueOf(SID), 1, 501L, LAT, LNG);
        assertEquals(200, r.get("code"));
        verify(roamMapper).insertShopVisitIgnore(anyLong(), anyLong(), anyInt(), anyLong(), any(), any());
    }
}
