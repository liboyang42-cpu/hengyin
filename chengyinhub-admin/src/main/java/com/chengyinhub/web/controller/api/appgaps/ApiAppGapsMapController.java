package com.chengyinhub.web.controller.api.appgaps;

import com.chengyinhub.business.appgaps.MapPublicLayers;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@Tag(name = "App 公共地图图层与同意留痕")
public class ApiAppGapsMapController extends BaseController {
    private final MapPublicLayers layers;

    public ApiAppGapsMapController(MapPublicLayers layers) { this.layers = layers; }

    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.appgaps.UsageConsentRecords usageConsent;

    /** App 使用同意留痕（只读，本人）。 */
    @org.springframework.web.bind.annotation.GetMapping("/api/compliance/usage-records")
    public AjaxResult usageRecords(@org.springframework.web.bind.annotation.RequestParam(value = "beforeId", required = false) String beforeId) {
        Long member = member();
        if (member == null) return AjaxResult.error(401, "请先登录");
        try { return AjaxResult.success(usageConsent.history(member, beforeId)); }
        catch (ServiceException e) { return AjaxResult.error(e.getCode() == null ? 400 : e.getCode(), e.getMessage()); }
    }

    @org.springframework.web.bind.annotation.GetMapping("/api/compliance/usage-records/request")
    public AjaxResult usageRecord(@org.springframework.web.bind.annotation.RequestParam(value = "requestId", required = false) String requestId) {
        Long member = member();
        if (member == null) return AjaxResult.error(401, "请先登录");
        try { return AjaxResult.success(usageConsent.original(member, requestId)); }
        catch (ServiceException e) { return AjaxResult.error(e.getCode() == null ? 400 : e.getCode(), e.getMessage()); }
    }

    private Long member() {
        try { return getAppUserId(); } catch (Exception e) { return null; }
    }

    @PostMapping("/api/map/public-layers")
    public AjaxResult publicLayers(@RequestBody(required = false) Map<String, Object> body) {
        try { if (getAppUserId() == null) return AjaxResult.error(401, "请先登录"); }
        catch (Exception e) { return AjaxResult.error(401, "请先登录"); }
        try { return AjaxResult.success(layers.read(body, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))); }
        catch (ServiceException e) { return AjaxResult.error(e.getCode() == null ? 400 : e.getCode(), e.getMessage()); }
    }
}
