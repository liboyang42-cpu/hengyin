package com.chengyinhub.business.service.support.rpg;

import com.chengyinhub.business.service.TencentMapClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TencentTopicWeatherProviderTest {

    @Test
    void cachesCityCoordsAndWeatherAndMapsChineseText() {
        TencentMapClient client = mock(TencentMapClient.class);
        when(client.isReady()).thenReturn(true);
        AtomicInteger geocodeCalls = new AtomicInteger();
        AtomicInteger weatherCalls = new AtomicInteger();
        when(client.get(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0, String.class);
            if (url.contains("/ws/geocoder/v1/")) {
                geocodeCalls.incrementAndGet();
                return "{\"status\":0,\"result\":{\"location\":{\"lat\":30.2741,\"lng\":120.1551}}}";
            }
            weatherCalls.incrementAndGet();
            return "{\"status\":0,\"result\":{\"realtime\":[{\"infos\":{\"weather\":\"中雨\"}}]}}";
        });

        MutableClock clock = new MutableClock(Instant.parse("2026-11-14T08:00:00Z"));
        TencentTopicWeatherProvider provider = new TencentTopicWeatherProvider(client, null, "", clock);

        assertEquals("rain", provider.currentWeather("杭州市"));
        assertEquals("rain", provider.currentWeather("杭州市"));
        assertEquals(1, geocodeCalls.get());
        assertEquals(1, weatherCalls.get());

        // Advance 13 minutes (> 12m weather TTL, < 24h coords TTL): weather re-fetches, coords reuse cache.
        clock.advanceSeconds(13 * 60L);
        assertEquals("rain", provider.currentWeather("杭州市"));
        assertEquals(1, geocodeCalls.get());
        assertEquals(2, weatherCalls.get());
    }

    @Test
    void mapsAllSupportedWeatherTextsAndCachesFailure() {
        assertEquals("clear", TencentTopicWeatherProvider.mapWeatherText("晴"));
        assertEquals("cloudy", TencentTopicWeatherProvider.mapWeatherText("多云"));
        assertEquals("rain", TencentTopicWeatherProvider.mapWeatherText("小雨"));
        assertEquals("snow", TencentTopicWeatherProvider.mapWeatherText("大雪"));
        assertEquals("fog", TencentTopicWeatherProvider.mapWeatherText("轻雾"));
        assertEquals("fog", TencentTopicWeatherProvider.mapWeatherText("中度霾"));
        assertEquals("wind", TencentTopicWeatherProvider.mapWeatherText("大风"));
        assertEquals("storm", TencentTopicWeatherProvider.mapWeatherText("雷阵雨"));
        assertNull(TencentTopicWeatherProvider.mapWeatherText("未知状态"));

        TencentMapClient client = mock(TencentMapClient.class);
        when(client.isReady()).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        when(client.get(anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return "{\"status\":121,\"message\":\"limit\"}";
        });
        MutableClock clock = new MutableClock(Instant.parse("2026-11-14T08:00:00Z"));
        TencentTopicWeatherProvider provider = new TencentTopicWeatherProvider(client, null, "", clock);

        assertNull(provider.currentWeather("成都"));
        assertNull(provider.currentWeather("成都"));
        assertEquals(1, calls.get());
    }

    @Test
    void respectsFixedOverrideWithoutOutboundCalls() {
        TencentMapClient client = mock(TencentMapClient.class);
        TencentTopicWeatherProvider provider = new TencentTopicWeatherProvider(client, null, "snow", Clock.systemUTC());
        assertEquals("snow", provider.currentWeather("上海"));
        assertNull(provider.currentWeather(" "));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant initial) {
            this.instant = initial;
        }

        void advanceSeconds(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
