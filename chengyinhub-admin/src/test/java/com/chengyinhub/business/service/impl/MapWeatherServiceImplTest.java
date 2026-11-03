package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.vo.MapWeatherVO;
import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.common.core.redis.RedisCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只替换出站 HTTP(requestWeather);腾讯 Key 在本环境不可调用,响应样例按腾讯官方
 * tencentmap-webservice-skill 文档的 type=now 结构:result.realtime[0].infos.weather。
 */
class MapWeatherServiceImplTest {

    private final List<String> urls = new ArrayList<>();
    private final Map<String, Object> redis = new HashMap<>();
    private String nextResponse;
    private RuntimeException nextFailure;
    private TencentMapClient client;
    private RedisCache redisCache;
    private MapWeatherServiceImpl service;

    private static String now(String weather) {
        return "{\"status\":0,\"message\":\"Success\",\"result\":{\"realtime\":[{\"province\":\"浙江省\","
                + "\"city\":\"杭州市\",\"district\":\"西湖区\",\"adcode\":330106,\"update_time\":\"2026-11-03 11:05\","
                + "\"infos\":{\"weather\":\"" + weather + "\",\"temperature\":12,\"wind_direction\":\"东北风\","
                + "\"wind_power\":\"3-4级\",\"humidity\":80,\"air_pressure\":1018}}]}}";
    }

    @BeforeEach
    void setUp() {
        client = mock(TencentMapClient.class);
        when(client.isReady()).thenReturn(true);
        redisCache = mock(RedisCache.class);
        when(redisCache.getCacheObject(anyString())).thenAnswer(inv -> redis.get(inv.<String>getArgument(0)));
        doAnswer(inv -> redis.put(inv.getArgument(0), inv.getArgument(1)))
                .when(redisCache).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));
        service = new MapWeatherServiceImpl() {
            @Override
            protected String requestWeather(String url) {
                urls.add(url);
                if (nextFailure != null) throw nextFailure;
                return nextResponse;
            }
        };
        ReflectionTestUtils.setField(service, "tencentMapClient", client);
        ReflectionTestUtils.setField(service, "redisCache", redisCache);
    }

    @Test
    void mapsRainSnowFogAndClearFromTheRealtimeWeatherText() {
        String[][] cases = {
                {"小雨", "rain"}, {"雷阵雨", "rain"}, {"大暴雨", "rain"}, {"中雪", "snow"}, {"雨夹雪", "snow"},
                {"大雾", "fog"}, {"轻雾", "fog"}, {"中度霾", "fog"}, {"晴天", "clear"}, {"多云", "clear"}, {"阴天", "clear"}};
        double lat = 10D;
        for (String[] c : cases) {
            nextResponse = now(c[0]);
            lat += 1D; // 每个用例落在不同网格,避免命中缓存
            MapWeatherVO vo = service.weatherAt(lat, 120.15D);
            assertEquals(c[1], vo.getKind(), c[0]);
            if ("clear".equals(c[1])) assertEquals(0D, vo.getIntensity(), c[0]);
            else assertTrue(vo.getIntensity() > 0D && vo.getIntensity() <= 1D, c[0]);
        }
        assertTrue(MapWeatherServiceImpl.fromWeatherText("大暴雨").getIntensity()
                > MapWeatherServiceImpl.fromWeatherText("小雨").getIntensity());
        assertTrue(MapWeatherServiceImpl.fromWeatherText("大雾").getIntensity()
                > MapWeatherServiceImpl.fromWeatherText("轻雾").getIntensity());
    }

    @Test
    void sameRoundedAreaIsServedFromCacheWithinTtlWithoutASecondOutboundCall() {
        nextResponse = now("中雨");
        MapWeatherVO first = service.weatherAt(30.2741D, 120.1551D);
        nextResponse = now("晴天");
        MapWeatherVO second = service.weatherAt(30.2790D, 120.1520D); // 同一 0.05° 网格
        assertEquals(1, urls.size());
        assertEquals("rain", first.getKind());
        assertEquals("rain", second.getKind());
        assertEquals(first.getIntensity(), second.getIntensity());
        verify(redisCache).setCacheObject(eq("map:weather:605:2403"), eq("rain|0.6"),
                eq(MapWeatherServiceImpl.TTL_MINUTES), eq(TimeUnit.MINUTES));
        // 发给供应商的是网格中心,不是玩家精确坐标
        assertTrue(urls.get(0).startsWith("https://apis.map.qq.com/ws/weather/v1/?type=now&location=30.275,120.175"),
                urls.get(0));
        assertFalse(urls.get(0).contains("key="));

        service.weatherAt(30.40D, 120.1551D); // 另一网格才再打一次
        assertEquals(2, urls.size());
    }

    @Test
    void ttlStaysWithinTenToFifteenMinutes() {
        assertTrue(MapWeatherServiceImpl.TTL_MINUTES >= 10 && MapWeatherServiceImpl.TTL_MINUTES <= 15);
    }

    @Test
    void upstreamExceptionRejectedStatusAndGarbageAllFailSilentAsClear() {
        nextFailure = new com.chengyinhub.common.utils.http.HttpRequestException("map total timeout", 0, false);
        assertEquals("clear", service.weatherAt(30D, 120D).getKind());
        nextFailure = null;
        nextResponse = "{\"status\":311,\"message\":\"key格式错误\"}";
        assertEquals("clear", service.weatherAt(31D, 120D).getKind());
        nextResponse = "not json";
        assertEquals("clear", service.weatherAt(32D, 120D).getKind());
        nextResponse = "{\"status\":0,\"result\":{\"realtime\":[]}}";
        assertEquals("clear", service.weatherAt(33D, 120D).getKind());
        // 失败只短暂记为晴
        verify(redisCache).setCacheObject(eq("map:weather:600:2400"), eq("clear|0.0"),
                eq(MapWeatherServiceImpl.FAILURE_TTL_MINUTES), eq(TimeUnit.MINUTES));
    }

    @Test
    void missingKeyOrBusyClientNeverCallsOutOrCaches() {
        when(client.isReady()).thenReturn(false);
        assertEquals("clear", service.weatherAt(30D, 120D).getKind());
        assertTrue(urls.isEmpty());
        when(client.isReady()).thenReturn(true);
        nextResponse = null; // TencentMapClient.get 在名额满时返回 null
        assertEquals("clear", service.weatherAt(30D, 120D).getKind());
        assertTrue(redis.isEmpty());
    }

    @Test
    void redisFailureStillAnswers() {
        when(redisCache.getCacheObject(anyString())).thenThrow(new IllegalStateException("redis down"));
        doThrow(new IllegalStateException("redis down"))
                .when(redisCache).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));
        nextResponse = now("小雪");
        assertEquals("snow", service.weatherAt(45D, 126D).getKind());
    }

    @Test
    void parseIgnoresEverythingButTheWeatherText() {
        assertNull(MapWeatherServiceImpl.parse(null));
        assertNull(MapWeatherServiceImpl.parse("{\"status\":0,\"result\":{}}"));
        assertEquals("rain", MapWeatherServiceImpl.parse(now("阵雨")).getKind());
    }
}
