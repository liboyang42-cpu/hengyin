package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.ITopicRouteService;
import com.chengyinhub.business.service.WalkRoute;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MPP-7：/api/play/walk-route 的登录、本局成员、站点归属、距离上限与限流。 */
class ApiPlayWalkRouteControllerTest {

    @Test
    void walkRouteNeedsALoggedInPlayerWithARunOnTheNodesTopic() {
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        ITopicRouteService routes = mock(ITopicRouteService.class);
        ApiPlayWalkRouteController controller = spy(new ApiPlayWalkRouteController());
        ReflectionTestUtils.setField(controller, "nodeService", nodes);
        ReflectionTestUtils.setField(controller, "routeRuntimeService", runtime);
        ReflectionTestUtils.setField(controller, "topicRouteService", routes);

        doThrow(new RuntimeException("no token")).when(controller).getAppUserId();
        assertEquals("ROUTE_LOGIN_REQUIRED", controller.walkRoute("10", "5", "31.23", "121.47", null).get("errorCode"));

        doReturn(7L).when(controller).getAppUserId();
        assertEquals("ROUTE_BAD_REQUEST", controller.walkRoute("10", "5", "abc", "121.47", null).get("errorCode"));
        assertEquals("ROUTE_BAD_REQUEST", controller.walkRoute("10", "0", "31.23", "121.47", null).get("errorCode"));

        CmsTopicNode other = node(11L, "31.2315", "121.4712");
        when(nodes.selectCmsTopicNodeById(5L)).thenReturn(other);
        assertEquals("ROUTE_NODE_NOT_FOUND", controller.walkRoute("10", "5", "31.23", "121.47", null).get("errorCode"));

        CmsTopicNode stop = node(10L, "31.2315", "121.4712");
        when(nodes.selectCmsTopicNodeById(5L)).thenReturn(stop);
        when(runtime.hasRunningSession(7L, 10L, 0L)).thenReturn(false);
        AjaxResult notPlaying = controller.walkRoute("10", "5", "31.23", "121.47", null);
        assertEquals(403, notPlaying.get(AjaxResult.CODE_TAG));
        assertEquals("ROUTE_NOT_PLAYING", notPlaying.get("errorCode"));

        when(runtime.hasRunningSession(7L, 10L, 0L)).thenReturn(true);
        assertEquals("ROUTE_TOO_FAR", controller.walkRoute("10", "5", "39.90", "116.40", null).get("errorCode"));
        verify(routes, never()).walkRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());

        when(routes.walkRoute(31.23, 121.47, 31.2315, 121.4712)).thenReturn(null);
        assertEquals("ROUTE_UNAVAILABLE", controller.walkRoute("10", "5", "31.23", "121.47", null).get("errorCode"));

        when(routes.walkRoute(31.23, 121.47, 31.2315, 121.4712)).thenReturn(new WalkRoute(420, 360,
                Arrays.asList(new double[] { 31.23, 121.47 }, new double[] { 31.2315, 121.4712 })));
        AjaxResult ok = controller.walkRoute("10", "5", "31.23", "121.47", null);
        assertEquals(200, ok.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ok.get(AjaxResult.DATA_TAG);
        assertEquals(420, data.get("distanceM"));
        assertEquals(360, data.get("durationS"));
    }

    @Test
    void walkRouteIsRateLimitedPerMember() throws Exception {
        RateLimiter limiter = ApiPlayWalkRouteController.class
                .getMethod("walkRoute", String.class, String.class, String.class, String.class, String.class)
                .getAnnotation(RateLimiter.class);
        assertNotNull(limiter);
        assertEquals(60, limiter.time());
        assertEquals(12, limiter.count());
        assertEquals(LimitType.MEMBER, limiter.limitType());
    }

    private static CmsTopicNode node(Long topicId, String lat, String lng) {
        CmsTopicNode node = new CmsTopicNode();
        node.setTopicId(topicId);
        node.setLatitude(lat);
        node.setLongitude(lng);
        return node;
    }
}
