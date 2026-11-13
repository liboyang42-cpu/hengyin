package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.PlayerGapsService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** App player routes on existing tables: own follow lists, own route-action receipt, route egg collection. */
@RestController
public class ApiPlayerGapsController extends BaseController {
    private final PlayerGapsService service;

    public ApiPlayerGapsController(PlayerGapsService service) { this.service = service; }

    private Long member() {
        try {
            return getAppUserId();
        } catch (Exception e) {
            throw new ServiceException("请先登录", 401);
        }
    }

    @GetMapping("/api/user/follow-list")
    public AjaxResult followList(@RequestParam String kind, @RequestParam String cursor, @RequestParam String limit) {
        return success(service.followList(member(), kind, cursor, limit));
    }

    @GetMapping("/api/play/route-action")
    public AjaxResult routeAction(@RequestParam Long sessionId, @RequestParam Long memberId, @RequestParam String actionId) {
        return success(service.routeAction(member(), sessionId, memberId, actionId));
    }

    @PostMapping("/api/play/egg/collect")
    public AjaxResult collectEgg(Long eggId, Long topicId) {
        service.collectEgg(member(), eggId, topicId);
        return success();
    }
}
