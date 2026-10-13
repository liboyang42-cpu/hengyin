package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.common.utils.http.HttpRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 正向地理编码(地址 → 建议坐标)。
 *
 * 这里最要紧的一条不是「能不能拿到坐标」,而是「拿不准时会不会假装拿到了」——
 * 供应商匹配不上地址时不报错,而是回退到区/市中心并照常返回 status=0。
 * 采信那种坐标 = 把据点静默插到错误位置,所以低可信度必须当失败。
 */
class MapGeocodeSuggestCoordsTest {

    /** 门牌级命中(reliability=9)才给坐标,且经纬度不能装反。 */
    @Test
    void returnsCoordsWhenProviderMatchesToStreetLevel() {
        StubGeocodeService service = new StubGeocodeService(
                "{\"status\":0,\"result\":{\"reliability\":9,\"location\":{\"lng\":120.15514,\"lat\":30.27411}}}");
        double[] coords = service.suggestCoords("杭州市上城区延安路1号", "杭州");

        assertNotNull(coords, "门牌级命中应当给出建议坐标");
        assertEquals(120.15514D, coords[0], 1e-6, "第 0 位是经度");
        assertEquals(30.27411D, coords[1], 1e-6, "第 1 位是纬度");
    }

    /**
     * 核心闸:供应商回退到城市中心(reliability 低)时必须失败。
     * 这条坐标本身是合法的杭州坐标,只有可信度能把它和真实命中区分开 ——
     * 所以断言必须指认到「可信度不足」这道闸,不能只看返回值非空。
     */
    @Test
    void rejectsCityCenterFallbackWhenReliabilityTooLow() {
        StubGeocodeService service = new StubGeocodeService(
                "{\"status\":0,\"result\":{\"reliability\":4,\"location\":{\"lng\":120.15514,\"lat\":30.27411}}}");

        assertNull(service.suggestCoords("杭州市某个不存在的地方", "杭州"),
                "可信度不足=供应商回退到了区/市中心,必须显式失败而不是采信这个坐标");
    }

    /** 边界:恰好压在门槛上的应当通过,门槛不能被悄悄提成 8。 */
    @Test
    void acceptsExactlyAtReliabilityThreshold() {
        StubGeocodeService service = new StubGeocodeService(
                "{\"status\":0,\"result\":{\"reliability\":7,\"location\":{\"lng\":120.1,\"lat\":30.2}}}");

        assertNotNull(service.suggestCoords("杭州市上城区延安路1号", "杭州"), "可信度 7 是门牌级,应当通过");
    }

    /** 走的是正向接口(address=),不是逆地理那条(location=)。 */
    @Test
    void callsForwardGeocodeEndpointWithRegion() {
        StubGeocodeService service = new StubGeocodeService(
                "{\"status\":0,\"result\":{\"reliability\":9,\"location\":{\"lng\":120.1,\"lat\":30.2}}}");
        service.suggestCoords("延安路1号", "杭州");

        assertTrue(service.lastUrl.contains("address="), "应当调用正向地理编码");
        assertTrue(service.lastUrl.contains("region="), "城市应当带上,否则重名路会落到别的城市");
    }

    /** 供应商明确报错。 */
    @Test
    void returnsNullWhenProviderRejects() {
        StubGeocodeService service = new StubGeocodeService("{\"status\":348,\"message\":\"参数错误\"}");

        assertNull(service.suggestCoords("延安路1号", "杭州"));
    }

    /** 地址为空不该发请求。 */
    @Test
    void returnsNullForBlankAddress() {
        StubGeocodeService service = new StubGeocodeService(
                "{\"status\":0,\"result\":{\"reliability\":9,\"location\":{\"lng\":120.1,\"lat\":30.2}}}");

        assertNull(service.suggestCoords("   ", "杭州"));
        assertNull(service.lastUrl, "空地址不该打到供应商");
    }

    /** 未配置密钥时安静失败,不能假装定位成功。 */
    @Test
    void returnsNullWhenMapClientNotConfigured() {
        TencentMapClient mapClient = mock(TencentMapClient.class);
        when(mapClient.isReady()).thenReturn(false);
        MapGeocodeServiceImpl service = new MapGeocodeServiceImpl();
        ReflectionTestUtils.setField(service, "tencentMapClient", mapClient);

        assertNull(service.suggestCoords("延安路1号", "杭州"));
    }

    /** 网络异常降级为「没定到」,不冒泡也不给默认坐标。 */
    @Test
    void networkFailureDegradesToNoCoords() {
        TencentMapClient mapClient = mock(TencentMapClient.class);
        when(mapClient.isReady()).thenReturn(true);
        when(mapClient.get(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new HttpRequestException("HTTP request failed", -1, true));
        MapGeocodeServiceImpl service = new MapGeocodeServiceImpl();
        ReflectionTestUtils.setField(service, "tencentMapClient", mapClient);

        assertNull(service.suggestCoords("延安路1号", "杭州"));
    }

    private static final class StubGeocodeService extends MapGeocodeServiceImpl {
        private final String payload;
        private String lastUrl;

        private StubGeocodeService(String payload) {
            this.payload = payload;
            TencentMapClient mapClient = mock(TencentMapClient.class);
            when(mapClient.isReady()).thenReturn(true);
            ReflectionTestUtils.setField(this, "tencentMapClient", mapClient);
        }

        @Override
        protected String requestGeocode(String url) {
            this.lastUrl = url;
            return payload;
        }
    }
}
