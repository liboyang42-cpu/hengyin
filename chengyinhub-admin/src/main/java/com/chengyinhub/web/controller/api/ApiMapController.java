package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.NearbyNodeVO;
import com.chengyinhub.business.service.IApiMapService;
import com.chengyinhub.business.service.IMapGeocodeService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * App 地图 —— 周边节点(只读)。
 * 供 App 城市地图页拉取「我周围有什么」。数据来自 cms_registration_merchant 已有经纬度。
 *
 */
@RestController
@Tag(name = "地图")
@RequestMapping("/api/map")
public class ApiMapController extends BaseController {

    /** 半径上限(米),防滥用做全城扫描 */
    private static final double MAX_RADIUS = 50000D;
    /** 单次返回上限 */
    private static final int MAX_LIMIT = 200;

    @Autowired
    private IApiMapService apiMapService;

    @Autowired
    private IMapGeocodeService mapGeocodeService;

    @Value("${tencent.map.reverse-geocode-per-minute:60}")
    private int reverseGeocodePerMinute;

    private long reverseGeocodeWindowStartedAt = System.currentTimeMillis();
    private int reverseGeocodeRequestCount;

    @PostMapping("/nearby")
    @Operation(summary = "周边节点")
    @Parameters({
            @Parameter(name = "longitude", description = "查询点经度(必填)"),
            @Parameter(name = "latitude", description = "查询点纬度(必填)"),
            @Parameter(name = "radius", description = "半径(米),默认 2000,上限 50000"),
            @Parameter(name = "limit", description = "数量上限,默认 50,上限 200"),
            @Parameter(name = "status", description = "可选:活动状态过滤"),
            @Parameter(name = "auditStatus", description = "可选:审核状态过滤")
    })
    public AjaxResult nearby(String longitude, String latitude, String radius, String limit,
                             String status, String auditStatus) {
        if (StringUtils.isEmpty(longitude) || StringUtils.isEmpty(latitude)) {
            return AjaxResult.error("缺少经纬度参数");
        }
        Double lng = Convert.toDouble(longitude);
        Double lat = Convert.toDouble(latitude);
        if (lng == null || lat == null) {
            return AjaxResult.error("经纬度格式不正确");
        }

        double r = Convert.toDouble(radius, 2000D);
        if (r <= 0) {
            r = 2000D;
        }
        if (r > MAX_RADIUS) {
            r = MAX_RADIUS;
        }

        int lim = Convert.toInt(limit, 50);
        if (lim <= 0) {
            lim = 50;
        }
        if (lim > MAX_LIMIT) {
            lim = MAX_LIMIT;
        }

        Integer st = StringUtils.isEmpty(status) ? null : Convert.toInt(status);
        Integer au = StringUtils.isEmpty(auditStatus) ? null : Convert.toInt(auditStatus);

        List<NearbyNodeVO> list = apiMapService.nearbyNodes(lng, lat, r, lim, st, au);
        return AjaxResult.success(list);
    }

    @PostMapping("/reverse-geocode")
    @Operation(summary = "逆地理编码城市")
    public AjaxResult reverseGeocode(String longitude, String latitude) {
        if (StringUtils.isEmpty(longitude) || StringUtils.isEmpty(latitude)) {
            return AjaxResult.error("缺少经纬度参数");
        }
        Double lng = Convert.toDouble(longitude);
        Double lat = Convert.toDouble(latitude);
        if (lng == null || lat == null || lng < -180D || lng > 180D || lat < -90D || lat > 90D) {
            return AjaxResult.error("经纬度格式不正确");
        }
        if (!tryConsumeReverseGeocodeBudget()) {
            return manualEntryFallback("MAP_RATE_LIMITED");
        }

        String city = mapGeocodeService.resolveCity(lng, lat);
        if (StringUtils.isEmpty(city)) {
            return manualEntryFallback("MAP_UNAVAILABLE");
        }
        return AjaxResult.success(Collections.singletonMap("city", city));
    }

    /** 关键词最长字数:地点名够用,也挡住把整段文本塞进来刷供应商额度 */
    private static final int MAX_KEYWORD_LENGTH = 40;

    /** 2026-10-28 漫游地图顶部搜地点(业主:搜「海星市场」要能搜到)。与逆地理共用同一份每分钟额度。 */
    @PostMapping("/place-search")
    @Operation(summary = "关键词搜地点")
    public AjaxResult placeSearch(String keyword, String longitude, String latitude) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty() || kw.length() > MAX_KEYWORD_LENGTH) {
            return AjaxResult.error("请输入要搜索的地点");
        }
        Double lng = Convert.toDouble(longitude);
        Double lat = Convert.toDouble(latitude);
        if (lng == null || lat == null || lng < -180D || lng > 180D || lat < -90D || lat > 90D) {
            return AjaxResult.error("经纬度格式不正确");
        }
        if (!tryConsumeReverseGeocodeBudget()) {
            return AjaxResult.success(Collections.emptyList());
        }
        return AjaxResult.success(mapGeocodeService.searchPlaces(kw, lng, lat));
    }

    private synchronized boolean tryConsumeReverseGeocodeBudget() {
        long now = System.currentTimeMillis();
        if (now - reverseGeocodeWindowStartedAt >= 60 * 1000L) {
            reverseGeocodeWindowStartedAt = now;
            reverseGeocodeRequestCount = 0;
        }
        int limit = reverseGeocodePerMinute > 0 ? reverseGeocodePerMinute : 60;
        if (reverseGeocodeRequestCount >= limit) {
            return false;
        }
        reverseGeocodeRequestCount++;
        return true;
    }

    private AjaxResult manualEntryFallback(String reason) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("city", "");
        fallback.put("manualInputRequired", true);
        fallback.put("reason", reason);
        return AjaxResult.success(fallback);
    }
}
