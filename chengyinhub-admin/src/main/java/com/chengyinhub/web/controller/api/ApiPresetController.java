package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsPresetRoute;
import com.chengyinhub.business.domain.vo.PresetRouteGuestProjection;
import com.chengyinhub.business.service.IPresetService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 建议版发布预设(风格/路线)只读接口
 *
 * @author chengyin
 */
@RestController
@Tag(name = "建议版预设")
@RequestMapping("/api/preset")
public class ApiPresetController extends BaseController {

    @Autowired
    private IPresetService presetService;

    @Operation(summary = "主题风格列表")
    @GetMapping("/style/list")
    public AjaxResult styleList() {
        return AjaxResult.success(presetService.listStyles());
    }

    @Operation(summary = "城市路线列表")
    @GetMapping("/route/list")
    public AjaxResult routeList(@RequestParam(required = false) String city) {
        List<CmsPresetRoute> routes = presetService.listRoutes(city);
        // 第九轮 m4:游客可读(SecurityConfig 精确放行),游客响应走公开字段投影。
        return AjaxResult.success(isGuest() ? PresetRouteGuestProjection.applyAll(routes) : routes);
    }

    @Operation(summary = "单条路线含站点")
    @GetMapping("/route/info")
    public AjaxResult routeInfo(@RequestParam Long id) {
        CmsPresetRoute route = presetService.getRouteWithNodes(id);
        if (route == null) {
            return AjaxResult.error("路线不存在");
        }
        return AjaxResult.success(isGuest() ? PresetRouteGuestProjection.apply(route) : route);
    }

    /** 未登录(或令牌无效)按游客处理;只决定响应投影,不放宽任何可见性。 */
    private boolean isGuest() {
        try {
            Long uid = getAppUserId();
            return uid == null || uid <= 0;
        } catch (Exception e) {
            return true;
        }
    }
}
