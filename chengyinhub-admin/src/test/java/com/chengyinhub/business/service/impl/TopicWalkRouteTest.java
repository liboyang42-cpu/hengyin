package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.business.service.WalkRoute;
import com.chengyinhub.common.utils.http.HttpRequestException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MPP-7：步行导航的解码、20 米取整缓存与降级。 */
class TopicWalkRouteTest {

    private static final String ROUTE = "{\"status\":0,\"result\":{\"routes\":[{\"distance\":420,\"duration\":6,"
            + "\"polyline\":[31.230000,121.470000,1000,2000,500,-1000]}]}}";

    @Test
    void decodesDistanceDurationAndCompressedPolyline() {
        TencentMapClient client = readyClient();
        when(client.get(anyString())).thenReturn(ROUTE);
        WalkRoute route = service(client).walkRoute(31.2301, 121.4701, 31.2315, 121.4712);
        assertNotNull(route);
        assertEquals(420, route.getDistanceM());
        assertEquals(360, route.getDurationS());
        List<double[]> line = route.getPolyline();
        assertEquals(3, line.size());
        assertArrayEquals(new double[] { 31.23, 121.47 }, line.get(0), 1e-9);
        assertArrayEquals(new double[] { 31.231, 121.472 }, line.get(1), 1e-9);
        assertArrayEquals(new double[] { 31.2315, 121.471 }, line.get(2), 1e-9);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(client).get(url.capture());
        assertTrue(url.getValue().contains("/direction/v1/walking/"), url.getValue());
        @SuppressWarnings("unchecked")
        List<List<Double>> view = (List<List<Double>>) route.toView().get("polyline");
        assertEquals(3, view.size());
    }

    @Test
    void originsInTheSame20MetreCellShareOneCachedRouteForSixtySeconds() {
        TencentMapClient client = readyClient();
        when(client.get(anyString())).thenReturn(ROUTE);
        long[] now = { 0L };
        TopicRouteServiceImpl service = new TopicRouteServiceImpl() {
            @Override protected long nanoTime() { return now[0]; }
        };
        ReflectionTestUtils.setField(service, "tencentMapClient", client);
        ReflectionTestUtils.setField(service, "mode", "walking");
        double[] cell = TopicRouteServiceImpl.snapToGrid(31.23011, 121.47011);
        assertNotNull(service.walkRoute(cell[0], cell[1], 31.2315, 121.4712));
        assertNotNull(service.walkRoute(cell[0] + 0.00001, cell[1] + 0.00001, 31.2315, 121.4712));
        verify(client, times(1)).get(anyString());
        now[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(61);
        assertNotNull(service.walkRoute(cell[0], cell[1], 31.2315, 121.4712));
        verify(client, times(2)).get(anyString());
    }

    @Test
    void unreadyClientProviderFailureAndBadCoordinatesAreUnavailable() {
        TencentMapClient idle = mock(TencentMapClient.class);
        when(idle.isReady()).thenReturn(false);
        assertNull(service(idle).walkRoute(31.23, 121.47, 31.24, 121.48));
        verify(idle, never()).get(anyString());

        TencentMapClient failing = readyClient();
        when(failing.get(anyString())).thenThrow(new HttpRequestException("HTTP request failed", -1, true));
        assertNull(service(failing).walkRoute(31.23, 121.47, 31.24, 121.48));

        TencentMapClient rejected = readyClient();
        when(rejected.get(anyString())).thenReturn("{\"status\":347,\"message\":\"no route\"}");
        assertNull(service(rejected).walkRoute(31.23, 121.47, 31.24, 121.48));

        TencentMapClient ready = readyClient();
        assertNull(service(ready).walkRoute(Double.NaN, 121.47, 31.24, 121.48));
        assertNull(service(ready).walkRoute(31.23, 121.47, 95D, 121.48));
        verify(ready, never()).get(anyString());
    }

    @Test
    void snapKeepsAPointWithinTwentyMetres() {
        double[] snapped = TopicRouteServiceImpl.snapToGrid(31.230123, 121.470456);
        double dLat = (snapped[0] - 31.230123) * 111320D;
        double dLng = (snapped[1] - 121.470456) * 111320D * Math.cos(Math.toRadians(31.23));
        assertTrue(Math.hypot(dLat, dLng) <= 15D, "snap moved the origin " + Math.hypot(dLat, dLng) + " m");
    }

    private TencentMapClient readyClient() {
        TencentMapClient client = mock(TencentMapClient.class);
        when(client.isReady()).thenReturn(true);
        return client;
    }

    private TopicRouteServiceImpl service(TencentMapClient client) {
        TopicRouteServiceImpl service = new TopicRouteServiceImpl();
        ReflectionTestUtils.setField(service, "tencentMapClient", client);
        ReflectionTestUtils.setField(service, "mode", "walking");
        return service;
    }
}
