package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.common.utils.http.HttpRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MapGeocodeServiceImplTest {

    // GAME-13: district lookup reads ad_info.adcode and the district name; anything else is "not found".
    @Test
    void districtComesFromAdcodeAndDistrictName() {
        java.util.Map<String, String> d = MapGeocodeServiceImpl.parseDistrict(com.alibaba.fastjson2.JSONObject.parseObject(
                "{\"status\":0,\"result\":{\"ad_info\":{\"adcode\":\"110105\"},\"address_component\":{\"district\":\"朝阳区\"}}}"));
        assertEquals("110105", d.get("areaCode"));
        assertEquals("朝阳区", d.get("areaName"));
        assertNull(MapGeocodeServiceImpl.parseDistrict(com.alibaba.fastjson2.JSONObject.parseObject(
                "{\"status\":0,\"result\":{\"ad_info\":{\"adcode\":\"1101\"},\"address_component\":{\"district\":\"x\"}}}")));
        assertNull(MapGeocodeServiceImpl.parseDistrict(com.alibaba.fastjson2.JSONObject.parseObject("{\"status\":311}")));
        assertNull(MapGeocodeServiceImpl.parseDistrict(null));
    }

    @Test
    void cachesRepeatedRequestsWithinTheSameCoordinateGrid() {
        RecordingMapGeocodeService service = new RecordingMapGeocodeService();
        TencentMapClient mapClient = mock(TencentMapClient.class);
        when(mapClient.isReady()).thenReturn(true);
        ReflectionTestUtils.setField(service, "tencentMapClient", mapClient);

        assertEquals("杭州市", service.resolveCity(120.15514D, 30.27411D));
        assertEquals("杭州市", service.resolveCity(120.15518D, 30.27418D));
        assertEquals(1, service.requestCount);
    }

    @Test
    void networkFailureSafelyDegradesToNoCity() {
        TencentMapClient mapClient = mock(TencentMapClient.class);
        when(mapClient.isReady()).thenReturn(true);
        when(mapClient.get(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new HttpRequestException("HTTP request failed", -1, true));
        MapGeocodeServiceImpl service = new MapGeocodeServiceImpl();
        ReflectionTestUtils.setField(service, "tencentMapClient", mapClient);

        assertNull(service.resolveCity(120.15514D, 30.27411D));
    }

    private static final class RecordingMapGeocodeService extends MapGeocodeServiceImpl {
        private int requestCount;

        @Override
        protected String requestGeocode(String url) {
            requestCount++;
            return "{\"status\":0,\"result\":{\"address_component\":{\"city\":\"杭州市\"}}}";
        }
    }
}
