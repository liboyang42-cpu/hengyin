package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.TencentMapClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 漫游地图搜地点(2026-10-28):只透出名称 / 地址 / 坐标 / 距离,供应商失败时返回空列表而不是报错。 */
class MapGeocodePlaceSearchTest {

    @Test
    void mapsSuggestionRowsToPlacesAndSkipsRowsWithoutLocation() {
        StubService service = new StubService(
                "{\"status\":0,\"result\":{\"address_component\":{\"city\":\"上海市\"}}}",
                "{\"status\":0,\"data\":[{\"title\":\"海星市场\",\"address\":\"闵行区莘庄镇\",\"location\":{\"lat\":31.11,\"lng\":121.38},\"_distance\":1203.6},"
                        + "{\"title\":\"无坐标\",\"address\":\"\"}]}");

        List<Map<String, Object>> places = service.searchPlaces(" 海星市场 ", 121.37, 31.10);

        assertEquals(1, places.size());
        assertEquals("海星市场", places.get(0).get("name"));
        assertEquals("闵行区莘庄镇", places.get(0).get("address"));
        assertEquals(31.11D, places.get(0).get("lat"));
        assertEquals(121.38D, places.get(0).get("lng"));
        assertEquals(1204L, places.get(0).get("distance"));
        String suggestion = service.urls.get(1);
        assertTrue(suggestion.contains("/ws/place/v1/suggestion/"), suggestion);
        assertTrue(suggestion.contains("location=31.1,121.37"), suggestion);
        assertTrue(suggestion.contains("region="), "用当前城市限定搜索范围");
        assertFalse(suggestion.contains("key="), "Key 由 TencentMapClient 统一追加,不出现在业务层");
    }

    @Test
    void returnsEmptyListWhenProviderRejectsOrIsNotReady() {
        StubService rejected = new StubService("{\"status\":0,\"result\":{}}", "{\"status\":311}");
        assertTrue(rejected.searchPlaces("海星市场", 121.37, 31.10).isEmpty());

        MapGeocodeServiceImpl notReady = new MapGeocodeServiceImpl();
        TencentMapClient client = mock(TencentMapClient.class);
        when(client.isReady()).thenReturn(false);
        ReflectionTestUtils.setField(notReady, "tencentMapClient", client);
        assertTrue(notReady.searchPlaces("海星市场", 121.37, 31.10).isEmpty());
    }

    private static final class StubService extends MapGeocodeServiceImpl {
        private final String[] replies;
        private final List<String> urls = new ArrayList<>();

        StubService(String... replies) {
            this.replies = replies;
            TencentMapClient client = mock(TencentMapClient.class);
            when(client.isReady()).thenReturn(true);
            ReflectionTestUtils.setField(this, "tencentMapClient", client);
        }

        @Override
        protected String requestGeocode(String url) {
            urls.add(url);
            return replies[Math.min(urls.size() - 1, replies.length - 1)];
        }
    }
}
