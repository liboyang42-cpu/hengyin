package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.map.MapPublicException;
import com.chengyinhub.business.domain.map.MapPublicQuery;
import com.chengyinhub.business.service.MapPublicService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 候选公开地图只读入口。不是 {@code /api/map/nearby}。
 * 不改 SecurityConfig：未登录由现有 authenticated 规则拒绝。这里再要求 App 玩家身份。
 */
@RestController
@Tag(name = "地图")
@RequestMapping("/api/map")
public class MapPublicController extends BaseController {

    @Autowired
    private MapPublicService mapPublicService;

    Long viewerId() {
        try {
            return getAppUserId();
        } catch (Exception ex) {
            return null;
        }
    }

    @PostMapping("/public-layers")
    @Operation(summary = "公开地图图层（候选只读，需登录）")
    public AjaxResult publicLayers(@RequestBody(required = false) MapPublicQuery query) {
        Long viewerId = viewerId();
        if (viewerId == null || viewerId <= 0) {
            return AjaxResult.error(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        try {
            return AjaxResult.success(mapPublicService.query(viewerId, query));
        } catch (MapPublicException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }
}
