package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.ITopicRouteService;
import com.chengyinhub.business.service.WalkRoute;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.LimitType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MPP-7：城市定向「在小程序里走过去」的步行路线（玩家当前位置 → 本局下一站）。
 *
 * <p>只读：腾讯路线规划步行模式，Key 只在后端（与章节 routeGeometry 同一个客户端）。坐标 GCJ-02。
 * 只给本人在该主题有进行中一局的会员，且站点必须属于该主题；起点离站点超过 20 公里不算路（不是步行）。
 */
@RestController
@Tag(name = "步行导航")
@RequestMapping("/api/play")
public class ApiPlayWalkRouteController extends BaseController {

    /** 起点离站点的最远距离（米）：再远就不是步行导航，避免被当成通用算路接口刷配额。 */
    static final double MAX_WALK_M = 20_000D;

    @Autowired(required = false)
    private ICmsTopicNodeService nodeService;

    @Autowired(required = false)
    private ITopicRouteRuntimeService routeRuntimeService;

    @Autowired(required = false)
    private ITopicRouteService topicRouteService;

    @Operation(summary = "本局下一站的步行路线：距离、时长与沿路坐标")
    @RateLimiter(time = 60, count = 12, limitType = LimitType.MEMBER)
    @GetMapping("/walk-route")
    public AjaxResult walkRoute(String topicId, String nodeId, String lat, String lng, String activityId) {
        Long memberId = currentMemberId();
        if (memberId == null) {
            return coded(HttpStatus.UNAUTHORIZED, "请先登录", "ROUTE_LOGIN_REQUIRED");
        }
        Long tid = Convert.toLong(topicId, 0L);
        Long nid = Convert.toLong(nodeId, 0L);
        Long aid = Convert.toLong(activityId, 0L);
        Double fromLat = Convert.toDouble(lat, null);
        Double fromLng = Convert.toDouble(lng, null);
        if (tid == null || tid <= 0L || nid == null || nid <= 0L || aid == null || aid < 0L
                || !validCoordinate(fromLat, fromLng)) {
            return coded(HttpStatus.BAD_REQUEST, "请求参数不完整", "ROUTE_BAD_REQUEST");
        }
        if (nodeService == null || routeRuntimeService == null || topicRouteService == null) {
            return coded(HttpStatus.ERROR, "步行路线暂时加载不了，请稍后再试", "ROUTE_UNAVAILABLE");
        }
        CmsTopicNode node = nodeService.selectCmsTopicNodeById(nid);
        if (node == null || !tid.equals(node.getTopicId())) {
            return coded(HttpStatus.NOT_FOUND, "这一站不在本主题里", "ROUTE_NODE_NOT_FOUND");
        }
        if (!routeRuntimeService.hasRunningSession(memberId, tid, aid)) {
            return coded(HttpStatus.FORBIDDEN, "开局后才能看步行路线", "ROUTE_NOT_PLAYING");
        }
        Double toLat = Convert.toDouble(node.getLatitude(), null);
        Double toLng = Convert.toDouble(node.getLongitude(), null);
        if (!validCoordinate(toLat, toLng)) {
            return coded(HttpStatus.ERROR, "这一站还没有位置", "ROUTE_UNAVAILABLE");
        }
        if (metres(fromLat, fromLng, toLat, toLng) > MAX_WALK_M) {
            return coded(HttpStatus.BAD_REQUEST, "离这一站太远，请用导航前往", "ROUTE_TOO_FAR");
        }
        WalkRoute route = topicRouteService.walkRoute(fromLat, fromLng, toLat, toLng);
        if (route == null || route.getPolyline().size() < 2) {
            return coded(HttpStatus.ERROR, "步行路线暂时加载不了，请稍后再试", "ROUTE_UNAVAILABLE");
        }
        return success(route.toView());
    }

    static boolean validCoordinate(Double lat, Double lng) {
        return lat != null && lng != null && Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90D && lat <= 90D && lng >= -180D && lng <= 180D && !(lat == 0D && lng == 0D);
    }

    static double metres(double lat1, double lng1, double lat2, double lng2) {
        double rad = Math.PI / 180D;
        double dLat = (lat2 - lat1) * rad;
        double dLng = (lng2 - lng1) * rad;
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.pow(Math.sin(dLng / 2), 2);
        return 12_742_000D * Math.asin(Math.min(1D, Math.sqrt(h)));
    }

    private static AjaxResult coded(int code, String message, String errorCode) {
        AjaxResult result = new AjaxResult(code, message);
        result.put("errorCode", errorCode);
        return result;
    }

    private Long currentMemberId() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0L ? null : id;
        } catch (Exception e) {
            return null;
        }
    }
}
